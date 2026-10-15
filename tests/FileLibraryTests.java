import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import juno.Main;
import juno.runtime.*;
import juno.runtime.RuntimeSupport.Module;
import static juno.runtime.RuntimeSupport.*;

/** Ownership/cancellation and actual bounded-heap streaming, beyond BSL value assertions. */
public final class FileLibraryTests {
    static void check(boolean ok,Object context) { if(!ok) throw new AssertionError(context); }
    static void fails(Runnable body) { try { body.run(); } catch(RuntimeException expected) { return; } throw new AssertionError("Expected error"); }
    static Object g(String name,Object...args) { return global(name,args); }
    static Object c(Object target,String name,Object...args) { return call(target,name,args); }
    static ApplicationContext context(Path directory) { return new ApplicationContext(directory,Map.of("TOKEN","before"),InputStream.nullInputStream(),OutputStream.nullOutputStream(),OutputStream.nullOutputStream()); }
    static final class Resource implements ApplicationContext.Resource {
        final AtomicBoolean closed=new AtomicBoolean();
        final CountDownLatch unblock=new CountDownLatch(1);
        final boolean fail;
        Resource(boolean fail) { this.fail=fail; }
        public void close() { closed.set(true); unblock.countDown(); if(fail) throw new Execution.Cancelled(); }
        public void cancel() { if(activeApp!=null) check(!Thread.holdsLock(activeApp),"I/O close under BackgroundJobs monitor"); close(); }
    }
    static volatile Resource jobResource;
    static volatile BackgroundJobs activeApp;
    static volatile ApplicationContext jobContext;
    static Path copySource,copyTarget;
    static CountDownLatch entered,gate;
    public static final class Worker implements Module {
        public Worker() {}
        public String __juno_metadata() { return "M\t0\tsnapshot\t0\nM\t0\tfail\t0\nM\t0\tblock\t0\nM\t0\tcopy\t0\n"; }
        public Object __juno_get(int n) { throw new AssertionError(); }
        public void __juno_set(int n,Object v) { throw new AssertionError(); }
        public Object __juno_call(int n,Object a,Object b,Object d) { return __juno_invoke(n,new Object[0]); }
        public Object __juno_invoke(int n,Object[] args) {
            ApplicationContext io=Execution.context(); jobContext=io; jobResource=io.own(new Resource(false)); entered.countDown();
            if(n==0) {
                try { gate.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); Execution.checkCancellation(); }
                Object result=construct("Structure",new Object[]{"Directory,Environment",io.directory().toString(),io.environment("TOKEN")});
                io.environment("TOKEN","child"); return result;
            }
            if(n==1) throw new BslException("expected worker failure");
            if(n==3) return g("CopyFile",copySource.toString(),copyTarget.toString());
            while(jobResource.unblock.getCount()!=0) try { jobResource.unblock.await(); } catch(InterruptedException ignored) { }
            Execution.checkCancellation(); return UNDEFINED;
        }
    }
    static void ownership(Path tmp) throws Exception {
        String processDir=System.getProperty("user.dir"); String processToken=System.getenv("TOKEN");
        Path one=Files.createDirectories(tmp.resolve("one")),two=Files.createDirectories(tmp.resolve("two"));
        class TrackingOutput extends ByteArrayOutputStream { boolean closed; public void close() { closed=true; } }
        TrackingOutput hostOut=new TrackingOutput();
        ApplicationContext first=new ApplicationContext(one,Map.of("TOKEN","first"),InputStream.nullInputStream(),hostOut,OutputStream.nullOutputStream());
        try(first; var scope=Execution.withContext(first)) {
            g("SetCurrentDirectory",two.toString()); first.environment("TOKEN","changed");
            try(var second=context(one);var nested=Execution.withContext(second)) {
                check(g("CurrentDirectory").equals(one.toString()),"separate cwd");
                check(second.environment("TOKEN").equals("before"),"separate environment");
            }
            check(g("CurrentDirectory").equals(two.toString()),"nested scope restores context");
            Object writer=construct("TextWriter",new Object[]{"owned.txt"}); c(writer,"write","flushed on scope exit");
            check(first.openResourceCount()==1,"writer is registered");
            fails(() -> ValueCopy.snapshot(writer));
            fails(() -> ValueCopy.snapshot(construct("BinaryDataBuffer",new Object[]{number("4")})));
        }
        check(first.openResourceCount()==0 && Files.size(two.resolve("owned.txt"))>3,"root cleanup flushes and closes writer");
        hostOut.write(1); check(!hostOut.closed && hostOut.size()==1,"host stream remains open");
        check(System.getProperty("user.dir").equals(processDir) && Objects.equals(System.getenv("TOKEN"),processToken),"no process-global mutations");

        ApplicationContext broken=context(one); Resource survivor=broken.own(new Resource(false)); broken.own(new Resource(true));
        try { broken.close(); throw new AssertionError("Expected cancellation Error"); } catch(Execution.Cancelled expected) { }
        check(survivor.closed.get() && broken.openResourceCount()==0,"cleanup continues after Error");
        Resource late=new Resource(false); fails(() -> broken.own(late)); check(late.closed.get(),"registration after close aborts handle");
        ApplicationContext flushing=context(one); CountDownLatch flushStarted=new CountDownLatch(1),abortFlush=new CountDownLatch(1);
        flushing.own(new ApplicationContext.Resource() {
            public void close() { flushStarted.countDown(); try { abortFlush.await(); } catch(InterruptedException e) { throw new AssertionError(e); } }
            public void cancel() { abortFlush.countDown(); }
        });
        Thread closing=Thread.ofPlatform().start(flushing::close);
        check(flushStarted.await(2,TimeUnit.SECONDS),"graceful close began"); flushing.cancel(); closing.join(2000);
        check(!closing.isAlive() && flushing.openResourceCount()==0,"cancel aborts an already-running graceful close");
        for(int i=0;i<50;i++) {
            ApplicationContext racing=context(one); Resource resource=new Resource(false);
            CountDownLatch start=new CountDownLatch(1);
            Thread register=Thread.ofPlatform().start(() -> { try { start.await(); racing.own(resource); } catch(InterruptedException e) { throw new AssertionError(e); } catch(BslException expected) { } });
            start.countDown(); racing.cancel(); register.join(); check(resource.closed.get(),"registration/cancel race");
        }
        try(var io=context(one);var scope=Execution.withContext(io)) {
            Object writer=construct("TextWriter",new Object[]{"bad-encoding.txt",IoEnumsForTests.ansi()});
            c(writer,"write","😀"); fails(() -> c(writer,"close"));
            check(io.openResourceCount()==0,"failed encoder close releases handle");
            Files.delete(one.resolve("bad-encoding.txt"));
            Object file=construct("File",new Object[]{one.toString()}); fails(() -> c(file,"size"));
            fails(() -> construct("FileStream",new Object[]{one.toString(),read(property(globalValue("fileopenmode"),"Open")),read(property(globalValue("fileaccess"),"Read"))}));
            Path executable=Files.writeString(one.resolve("executable"),"#!/bin/sh\nexit 0\n"),copied=one.resolve("executable-copy");
            var modified=java.nio.file.attribute.FileTime.from(java.time.Instant.parse("2020-01-01T00:00:00Z"));
            Files.setLastModifiedTime(executable,modified);
            var posix=Files.getFileAttributeView(executable,java.nio.file.attribute.PosixFileAttributeView.class);
            if(posix!=null) Files.setPosixFilePermissions(executable,java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
            else c(construct("File",new Object[]{executable.toString()}),"SetReadOnly",true);
            g("CopyFile",executable.toString(),copied.toString());
            check(Files.getLastModifiedTime(copied).equals(Files.getLastModifiedTime(executable)),"copy preserves mtime");
            if(posix!=null) check(Files.getPosixFilePermissions(copied).equals(Files.getPosixFilePermissions(executable)),"copy preserves executable permissions");
            else check(c(construct("File",new Object[]{copied.toString()}),"GetReadOnly").equals(true),"copy preserves DOS readonly");
            g("CopyFile",executable.toString(),executable.toString());
            g("FileCopy",executable.toString(),executable.toString());
            check(GlobalFunctions.names().get("copyfile")==GlobalFunctions.names().get("filecopy"),"platform and OneScript copy aliases share a signature");
            check(Files.readString(executable).equals("#!/bin/sh\nexit 0\n") && Files.getLastModifiedTime(executable).equals(modified),"self-copy preserves data and metadata");
            c(construct("File",new Object[]{executable.toString()}),"SetReadOnly",false);
            c(construct("File",new Object[]{copied.toString()}),"SetReadOnly",false);
        }
        // A mutable File object is not needed to validate enum snapshots; namespaces are immutable.
        check(ValueCopy.snapshot(globalValue("textencoding"))==globalValue("textencoding"),"enum transport");
    }
    static final class IoEnumsForTests { static Object ansi() { return read(property(globalValue("textencoding"),"ANSI")); } }
    static void background(Path tmp) throws Exception {
        Path one=Files.createDirectories(tmp.resolve("bg-one")),two=Files.createDirectories(tmp.resolve("bg-two"));
        copySource=one.resolve("sparse.bin"); copyTarget=two.resolve("copy.bin");
        try(RandomAccessFile file=new RandomAccessFile(copySource.toFile(),"rw")) { file.setLength(512L*1024*1024); }
        try(var app=new BackgroundJobs(Map.of("Worker",Worker.class),1,4,s -> {},context(one))) {
            activeApp=app;
            app.run(() -> {
                entered=new CountDownLatch(1); gate=new CountDownLatch(1);
                BackgroundJobs.Job job=app.execute("Worker.snapshot",UNDEFINED,UNDEFINED,"");
                check(entered.await(2,TimeUnit.SECONDS),"worker started");
                Execution.context().directory(two); Execution.context().environment("TOKEN","after"); gate.countDown();
                Object result=job.result();
                check(read(property(result,"Directory")).equals(one.toString()),"job snapshots cwd at submit");
                check(read(property(result,"Environment")).equals("before"),"job snapshots environment at submit");
                check(Execution.context().environment("TOKEN").equals("after"),"child environment isolated");
                check(jobResource.closed.get(),"successful job cleanup");
                entered=new CountDownLatch(1);
                BackgroundJobs.Job failed=app.execute("Worker.fail",UNDEFINED,UNDEFINED,""); fails(failed::result);
                check(jobResource.closed.get(),"failed job cleanup");
                entered=new CountDownLatch(1);
                BackgroundJobs.Job blocked=app.execute("Worker.block",UNDEFINED,UNDEFINED,"");
                check(entered.await(2,TimeUnit.SECONDS),"blocking I/O started");
                app.cancel(blocked);
                check(blocked.await(number("2")) && blocked.state().equals("Отменено"),"cancel closes blocking handle");
                check(jobResource.closed.get(),"cancel cleanup");
                entered=new CountDownLatch(1);
                BackgroundJobs.Job copying=app.execute("Worker.copy",UNDEFINED,UNDEFINED,"");
                check(entered.await(2,TimeUnit.SECONDS),"copy started");
                long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(jobContext.openResourceCount()<3 && System.nanoTime()<limit && copying.state().equals("Выполняется")) Thread.onSpinWait();
                check(jobContext.openResourceCount()>=3,"copy owns both channels while running");
                app.cancel(copying); check(copying.await(number("2")) && copying.state().equals("Отменено"),"CopyFile cancellation");
                check(jobContext.openResourceCount()==0 && Files.size(copyTarget)<Files.size(copySource),"copy releases handles before copying full file");
                Files.delete(copyTarget); Files.delete(copySource);
                return UNDEFINED;
            });
        } finally { activeApp=null; }
    }
    static void lint(Path tmp) throws Exception {
        Path root=Files.createDirectories(tmp.resolve("lint"));
        Files.writeString(root.resolve("A.bsl"),"Процедура А() КонецПроцедуры"); Files.writeString(root.resolve("B.bsl"),"Процедура Б() КонецПроцедуры");
        Path plugin=tmp.resolve("IoPlugin.bsl");
        Files.writeString(plugin,"""
            Перем Ч;
            Процедура Открыть(Парсер,Параметры) Экспорт
                Ч = Новый ЧтениеТекста(Параметры.Файл);
                УстановитьТекущийКаталог(Параметры.Каталог);
            КонецПроцедуры
            Функция Подписки() Экспорт Возврат Новый Массив; КонецФункции
            Функция Закрыть() Экспорт Возврат Ч.ПрочитатьСтроку(); КонецФункции
            """);
        Path data=Files.writeString(tmp.resolve("plugin-data.txt"),"I/O works");
        Path params=tmp.resolve("params.json");
        Files.writeString(params,juno.Json.stringify(Map.of("IoPlugin.bsl",Map.of("Файл",data.toString(),"Каталог",tmp.toString()))));
        String current=System.getProperty("user.dir");
        for(String jobs:List.of("1","4")) {
            ByteArrayOutputStream out=new ByteArrayOutputStream(),err=new ByteArrayOutputStream();
            int code=Main.execute(new String[]{"lint",root.toString(),"--plugin",plugin.toString(),"--params",params.toString(),"--jobs",jobs},new PrintStream(out),new PrintStream(err));
            check(code==0 && out.toString().contains("I/O works"),"lint jobs="+jobs+" "+out+err);
        }
        check(System.getProperty("user.dir").equals(current),"lint virtual cwd isolation");
        Files.delete(data); // Also fails on Windows if either per-file owner leaked the reader.
    }
    static void limitedHeap(Path tmp) throws Exception {
        Path project=Files.createDirectories(tmp.resolve("chunks")),source=project.resolve("in.bin"),target=project.resolve("out.bin");
        MessageDigest expected=MessageDigest.getInstance("SHA-256"); byte[] block=new byte[65536]; new Random(321).nextBytes(block);
        try(OutputStream out=Files.newOutputStream(source)) { for(int i=0;i<1024;i++) { out.write(block); expected.update(block); } out.write(7); expected.update((byte)7); }
        Path script=project.resolve("Copy.bsl"); Files.writeString(script,"""
            Источник = Новый ФайловыйПоток(АргументыКоманднойСтроки[0],РежимОткрытияФайла.Открыть,ДоступКФайлу.Чтение);
            Приемник = Новый ФайловыйПоток(АргументыКоманднойСтроки[1],РежимОткрытияФайла.Создать,ДоступКФайлу.Запись);
            Буфер = Новый БуферДвоичныхДанных(65536);
            Пока Истина Цикл
                Количество = Источник.Прочитать(Буфер,0,Буфер.Размер);
                Если Количество=0 Тогда Прервать; КонецЕсли;
                Приемник.Записать(Буфер,0,Количество);
            КонецЦикла;
            Источник.Закрыть(); Приемник.Закрыть();
            """);
        Path jar=Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx32m","-jar",jar.toString(),"run",script.toString(),source.toString(),target.toString()).redirectErrorStream(true).start();
        if(!process.waitFor(30,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("heap-limited stream timed out"); }
        String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8); check(process.exitValue()==0,output);
        MessageDigest actual=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(target)) { int count; while((count=in.read(block))>=0) actual.update(block,0,count); }
        check(Files.size(source)==Files.size(target) && Arrays.equals(expected.digest(),actual.digest()),"64 MiB+1 under 32 MiB heap preserves digest");
        Files.delete(source); Files.delete(target);
    }
    public static void main(String[] args) throws Exception {
        Path tmp=Files.createTempDirectory("juno-file-tests-");
        try { ownership(tmp); background(tmp); lint(tmp); limitedHeap(tmp); }
        finally { try(var paths=Files.walk(tmp)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
        System.out.println("File library PASS");
    }
}
