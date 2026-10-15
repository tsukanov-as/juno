import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import juno.Main;
import juno.Json;
import juno.runtime.*;
import juno.runtime.RuntimeSupport.Module;
import static juno.runtime.RuntimeSupport.*;

/** Actual children, native redirects, live cursors, cancellation and bounded-heap pressure. */
public final class ProcessLibraryTests {
    static final String JAVA=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
    static final String CP=String.join(File.pathSeparator,Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator))).map(s->Path.of(s).toAbsolutePath().toString()).toList());
    static void check(boolean ok,Object why){if(!ok)throw new AssertionError(why);}
    static void fails(Runnable action){try{action.run();}catch(RuntimeException expected){return;}throw new AssertionError("Expected failure");}
    static Object c(Object target,String name,Object...args){return call(target,name,args);}
    static Object g(String name,Object...args){return global(name,args);}
    static Object p(Object target,String name){return read(property(target,name));}
    static Object n(long number){return number(Long.toString(number));}
    static Object array(List<String> items){Object a=construct("Array",new Object[0]);for(String s:items)c(a,"Add",s);return a;}
    static List<String> command(String mode,String...tail){List<String> a=new ArrayList<>(List.of(JAVA,"-Dstdout.encoding=UTF-8","-Dstderr.encoding=UTF-8","-cp",CP,"ProcessLibraryTests","--fixture",mode));a.addAll(List.of(tail));return a;}
    static Object create(List<String> args,Object...options){Object[] all=new Object[options.length+1];all[0]=array(args);System.arraycopy(options,0,all,1,options.length);return g("CreateProcessWithArguments",all);}
    static Object process(String mode,String...tail){return create(command(mode,tail),"",true,true);}
    static ApplicationContext context(Path cwd,Map<String,String> env,OutputStream out,OutputStream err){Map<String,String> childEnv=new HashMap<>(env);if(System.getProperty("os.name").equals("Linux")){childEnv.put("LANG","C.UTF-8");childEnv.put("LC_ALL","C.UTF-8");}return new ApplicationContext(cwd,childEnv,new InputStream(){public int read(){throw new AssertionError("host stdin must never be read");}},out,err);}
    static void marker(Path path) throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!Files.exists(path)&&System.nanoTime()<end)Thread.sleep(10);check(Files.exists(path),"child marker "+path);}
    static byte[] block(int seed){byte[] b=new byte[65536];new Random(seed).nextBytes(b);return b;}
    static String digest(Path file) throws Exception {MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))>=0)d.update(b,0,n);}return HexFormat.of().formatHex(d.digest());}
    static String expected(int seed,int blocks) throws Exception {MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=block(seed);for(int i=0;i<blocks;i++)d.update(b);return HexFormat.of().formatHex(d.digest());}
    static void fixture(String mode,String[] args) throws Exception {
        switch(mode){
            case "args"->System.out.print(Json.stringify(List.of(args)));
            case "env"->{System.out.println(Path.of("").toAbsolutePath());System.out.println(System.getenv("JUNO_PROCESS_VALUE"));System.out.println(System.getenv("JUNO_PROCESS_EMPTY"));}
            case "exit"->System.exit(7);
            case "streams"->{System.out.print("A\n\nПривет\r\nlast");System.err.print("Ошибка\n");}
            case "input"->{byte[] b=System.in.readAllBytes();System.out.print(HexFormat.of().formatHex(b));System.err.print("err:"+b.length);}
            case "live"->{byte[] b="Я".getBytes(StandardCharsets.UTF_8);System.out.write(b,0,1);System.out.flush();Files.writeString(Path.of(args[0]),"ready");System.in.read();System.out.write(b,1,1);System.out.print("\n");}
            case "line"->{System.out.print("first");System.out.flush();Files.writeString(Path.of(args[0]),"ready");System.in.read();System.out.print("tail\r\nlast");}
            case "sleep"->{Files.writeString(Path.of(args[0]),"ready");Thread.sleep(60000);}
            case "codec"->System.out.write("Привет\n".getBytes(Charset.forName("windows-1251")));
            case "tree"->{Process child=new ProcessBuilder(command("sleep",args[1])).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();Files.writeString(Path.of(args[0]),Long.toString(child.pid()));Thread.sleep(500);}
            case "both"->{Thread out=Thread.ofPlatform().start(()->emit(System.out,1)),err=Thread.ofPlatform().start(()->emit(System.err,2));MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;while((n=System.in.read(b))>=0)d.update(b,0,n);out.join();err.join();Files.writeString(Path.of(args[0]),HexFormat.of().formatHex(d.digest()));}
            default->throw new AssertionError(mode);
        }
    }
    static void emit(OutputStream stream,int seed){try{byte[] b=block(seed);for(int i=0;i<1024;i++)stream.write(b);stream.flush();}catch(IOException e){throw new UncheckedIOException(e);}}
    static void basics(Path root) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream(),err=new ByteArrayOutputStream();
        try(var owner=context(root,Map.of(),out,err);var scope=Execution.withContext(owner)){
            Object process=process("args","a b","","Я","quote\"x","single'quote","semi;colon","dollar$HOME","back\\slash");check(valueType(process).equals(type("Process")),"process alias");check(p(process,"StdOut")==UNDEFINED,"before-start stream");fails(()->p(process,"HasExited"));c(process,"Start");c(p(process,"StdIn"),"Close");check(c(process,"WaitForExit",n(5000)).equals(true),"wait");check(Json.parse((String)c(p(process,"StdOut"),"Read")).equals(List.of("a b","","Я","quote\"x","single'quote","semi;colon","dollar$HOME","back\\slash")),"exact argv no shell");check(p(process,"ExitCode").equals(n(0)),"zero exit");fails(()->c(process,"Start"));c(process,"Close");c(process,"Close");
            Object streams=process("streams");c(streams,"Start");c(streams,"WaitForExit");Object stdout=p(streams,"StdOut");for(Object expected:List.of("A","","Привет","last",UNDEFINED))check(c(stdout,"ReadLine").equals(expected),"ReadLine and EOF");check(c(p(streams,"StdErr"),"Read").equals("Ошибка\n"),"separate stderr");c(streams,"Close");
            Object input=process("input");c(input,"Start");Object writer=p(input,"StdIn");c(writer,"Write","Я");Object binary=g("GetBinaryDataFromHexString","00FF");c(binary,"Write",writer);Object raw=construct("BinaryDataBuffer",new Object[]{n(4)});c(raw,"Set",n(2),n(255));c(writer,"WriteBytes",raw,n(1),n(2));c(raw,"Set",n(2),n(65));c(writer,"WriteBytes",raw,n(4),n(0));fails(()->c(writer,"WriteBytes",raw,n(5),n(0)));fails(()->c(writer,"WriteBytes",raw,n(0),n(-1)));fails(()->c(p(input,"StdOut"),"WriteBytes",raw,n(0),n(0)));c(writer,"Close");c(writer,"Close");c(input,"WaitForExit");check(c(p(input,"StdOut"),"Read").equals("d0af00ff00ff"),"text+raw stdin and EOF");fails(()->c(writer,"Write","x"));fails(()->c(writer,"WriteBytes",raw,n(0),n(0)));c(input,"Close");
            Object code=process("codec"); // explicit output codec does not change UTF-8 stdin
            c(code,"Close");code=create(command("codec"),"",true,false,"windows-1251");c(code,"Start");c(code,"WaitForExit");check(c(p(code,"StdOut"),"Read").equals("Привет\n"),"output codec");c(code,"Close");
            Object nonzero=process("exit");c(nonzero,"Start");c(nonzero,"WaitForExit");check(p(nonzero,"ExitCode").equals(n(7)),"nonzero is exit");c(nonzero,"Close");
            Object inherited=create(command("streams"));c(inherited,"Start");c(inherited,"WaitForExit");check(out.toString(StandardCharsets.UTF_8).equals("A\n\nПривет\r\nlast")&&err.toString(StandardCharsets.UTF_8).equals("Ошибка\n"),"context outputs forwarded exactly");c(inherited,"Close");
            fails(()->g("CreateProcess",array(command("exit"))));fails(()->g("CreateProcessWithArguments",array(List.of("a\0b"))));fails(()->g("CreateProcessWithArguments",array(List.of(""))));Object missing=create(List.of(root.resolve("absent").toString()));fails(()->c(missing,"Start"));check(owner.openResourceCount()==0,"basic errors/resources");
        }
    }
    static void live(Path root) throws Exception {
        try(var owner=context(root,Map.of(),OutputStream.nullOutputStream(),OutputStream.nullOutputStream());var scope=Execution.withContext(owner)){
            for(String mode:List.of("live","line")){
                Path ready=root.resolve(mode+".ready");Object process=process(mode,ready.toString());c(process,"Start");marker(ready);Object stream=p(process,"StdOut");long begin=System.nanoTime();check(c(stream,mode.equals("live")?"Read":"ReadLine")==UNDEFINED,"partial unicode/line not false EOF or replacement");check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin)<500,"nonblocking live read");check(p(process,"HasExited").equals(false),"child alive");c(p(process,"StdIn"),"Write","x");c(p(process,"StdIn"),"Close");c(process,"WaitForExit");check(c(stream,"ReadLine").equals(mode.equals("live")?"Я":"firsttail"),"partial cursor retained");if(mode.equals("line"))check(c(stream,"ReadLine").equals("last"),"CRLF final text");check(c(stream,"Read")==UNDEFINED,"stable EOF");c(process,"Close");
            }
        }
    }
    static void environment(Path root) throws Exception {
        Path cwd=root.resolve("child cwd");Files.createDirectory(cwd);
        try(var owner=context(root,Map.of("JUNO_PROCESS_VALUE","before"),OutputStream.nullOutputStream(),OutputStream.nullOutputStream());var scope=Execution.withContext(owner)){
            Object overlay=construct("Map",new Object[0]);c(overlay,"Insert","JUNO_PROCESS_EMPTY","");Object process=create(command("env"),"child cwd",true,false,UNDEFINED,overlay);c(overlay,"Insert","JUNO_PROCESS_VALUE","bad");owner.environment("JUNO_PROCESS_VALUE","after");c(process,"Start");owner.environment("JUNO_PROCESS_VALUE","later");c(process,"WaitForExit");String actual=(String)c(p(process,"StdOut"),"Read");String[] lines=actual.split("\\R",-1);check(Path.of(lines[0]).toRealPath().equals(cwd.toRealPath())&&lines.length==4&&lines[1].equals("after")&&lines[2].equals(""),"start env/cwd snapshot and create overlay snapshot: "+actual);c(process,"Close");
            if(!System.getProperty("os.name").startsWith("Windows")){Path bin=root.resolve("bin");Files.createDirectory(bin);Path executable=bin.resolve("juno-path-child");Files.writeString(executable,"#!/bin/sh\nprintf path-ok");check(executable.toFile().setExecutable(true),"executable bit");owner.environment("PATH","bin");Object viaPath=create(List.of("juno-path-child"),"",true);c(viaPath,"Start");c(viaPath,"WaitForExit");check(c(p(viaPath,"StdOut"),"Read").equals("path-ok"),"virtual relative PATH lookup");c(viaPath,"Close");Object relative=create(List.of("bin/juno-path-child"),"",true);c(relative,"Start");c(relative,"WaitForExit");check(c(p(relative,"StdOut"),"Read").equals("path-ok"),"relative executable through context cwd");c(relative,"Close");}
        }
    }
    static void timeoutStop(Path root) throws Exception {
        try(var owner=context(root,Map.of(),OutputStream.nullOutputStream(),OutputStream.nullOutputStream());var scope=Execution.withContext(owner)){
            Object process=process("sleep",root.resolve("timeout.ready").toString());c(process,"Start");check(c(process,"WaitForExit",n(1)).equals(false)&&p(process,"HasExited").equals(false),"timeout leaves child alive");fails(()->p(process,"ExitCode"));fails(()->c(process,"WaitForExit",n(2147483648L)));c(process,"Stop",true);check(c(process,"WaitForExit",n(2000)).equals(true),"stop/reap");c(process,"Close");
        }
        ApplicationContext owner=context(root,Map.of(),OutputStream.nullOutputStream(),OutputStream.nullOutputStream());long pid;
        try(var scope=Execution.withContext(owner)){
            Object process=process("tree",root.resolve("desc.pid").toString(),root.resolve("desc.ready").toString());c(process,"Start");marker(root.resolve("desc.ready"));c(process,"WaitForExit",n(5000));pid=Long.parseLong(Files.readString(root.resolve("desc.pid")));check(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),"descendant remains after root exit until scope close");
        }finally{owner.close();}
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)&&System.nanoTime()<limit)Thread.sleep(10);check(ProcessHandle.of(pid).map(h->!h.isAlive()).orElse(true),"known descendant killed on scope close");check(owner.openResourceCount()==0,"scope resources0");
    }
    static void slowHost(Path root) throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        OutputStream slow=new OutputStream(){public void write(int n){throw new AssertionError();}public void write(byte[] b,int off,int len) throws IOException {entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}bytes.write(b,off,len);}};
        try(var owner=context(root,Map.of(),slow,OutputStream.nullOutputStream());var scope=Execution.withContext(owner)){
            Object process=create(command("streams"));c(process,"Start");check(entered.await(3,TimeUnit.SECONDS),"slow host entered");long start=System.nanoTime();check(c(process,"WaitForExit",n(100)).equals(false),"finite wait includes forwarding deadline");check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<500,"slow sink does not defeat wait timeout");release.countDown();check(c(process,"WaitForExit",n(3000)).equals(true),"normal wait drains output");check(bytes.toString(StandardCharsets.UTF_8).equals("A\n\nПривет\r\nlast"),"normal forwarding not truncated");c(process,"Close");
        }finally{release.countDown();}
    }
    static void blockedInput(Path root) throws Exception {
        ApplicationContext owner=context(root,Map.of(),OutputStream.nullOutputStream(),OutputStream.nullOutputStream());Object process;
        try(var scope=Execution.withContext(owner)){process=process("sleep",root.resolve("blocked.ready").toString());c(process,"Start");}
        Object writer=p(process,"StdIn");CountDownLatch writing=new CountDownLatch(1);Thread thread=Thread.ofVirtual().start(()->{try(var scope=Execution.withContext(owner)){Object data=g("GetBinaryDataFromHexString","41".repeat(65536));writing.countDown();for(int i=0;i<1024;i++)c(data,"Write",writer);}catch(RuntimeException expected){}});
        check(writing.await(2,TimeUnit.SECONDS),"writer started");Thread.sleep(100);long start=System.nanoTime();owner.cancel();thread.join(3000);check(!thread.isAlive()&&TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<3000&&owner.openResourceCount()==0,"kill before stdin close unblocks writer");
    }
    static volatile Path jobRoot;static volatile ApplicationContext jobOwner;
    public static final class Worker implements Module {
        public String __juno_metadata(){return "M\t0\trun\t0\n";}public Object __juno_get(int n){throw new AssertionError();}public void __juno_set(int n,Object v){throw new AssertionError();}public Object __juno_call(int n,Object a,Object b,Object c){return __juno_invoke(n,new Object[0]);}
        public Object __juno_invoke(int n,Object[] args){jobOwner=Execution.context();Object process=process("sleep",jobRoot.resolve("job.ready").toString());c(process,"Start");c(process,"WaitForExit");return UNDEFINED;}
    }
    static void cancellation(Path root) throws Exception {jobRoot=root;try(var app=new BackgroundJobs(Map.of("Worker",Worker.class),1,2,s->{},context(root,Map.of(),OutputStream.nullOutputStream(),OutputStream.nullOutputStream()))){app.run(()->{var job=app.execute("Worker.run",UNDEFINED,UNDEFINED,"");marker(root.resolve("job.ready"));app.cancel(job);check(job.await(n(3))&&job.state().equals("Отменено")&&jobOwner.openResourceCount()==0,"background cancellation reaps process");return UNDEFINED;});}}
    static void parser(){
        check(ProcessArguments.unix("tool \"a b\" '' 'Я' back\\slash \"quote\\\"x\" $HOME ;").equals(List.of("tool","a b","","Я","back\\slash","quote\"x","$HOME",";")),"Unix grouping no shell");
        check(ProcessArguments.windows("\"C:\\Program Files\\tool.exe\" \"a b\" \"\" a\\\\\\\"b \"a\"\"b\"").equals(List.of("C:\\Program Files\\tool.exe","a b","","a\\\"b","a\"b")),"Windows CRT table");
        for(String s:List.of("","\"\" foo","tool \"oops","tool\0x")){fails(()->ProcessArguments.unix(s));fails(()->ProcessArguments.windows(s));}
    }
    static void rawToFile(Object stream,Path file) throws Exception {Object buffer=construct("BinaryDataBuffer",new Object[]{n(65536)}),output=construct("FileStream",new Object[]{file.toString(),p(globalValue("fileopenmode"),"Create"),p(globalValue("fileaccess"),"Write")});try{while(true){Object count=c(stream,"ReadBytes",buffer,n(0),n(65536));if(count.equals(n(0)))break;c(output,"Write",buffer,n(0),count);}}finally{c(output,"Close");}}
    static void largeChild(Path root) throws Exception {
        try(var owner=context(root,Map.of(),OutputStream.nullOutputStream(),OutputStream.nullOutputStream());var scope=Execution.withContext(owner)){
            Object process=process("both",root.resolve("stdin.sha").toString());c(process,"Start");Object bytes=g("GetBinaryDataFromHexString",HexFormat.of().formatHex(block(3)));for(int i=0;i<1024;i++)c(bytes,"Write",p(process,"StdIn"));c(p(process,"StdIn"),"Close");check(c(process,"WaitForExit",n(20000)).equals(true),"simultaneous output/input pressure");fails(()->c(p(process,"StdOut"),"Read"));rawToFile(p(process,"StdOut"),root.resolve("out.bin"));rawToFile(p(process,"StdErr"),root.resolve("err.bin"));check(digest(root.resolve("out.bin")).equals(expected(1,1024))&&digest(root.resolve("err.bin")).equals(expected(2,1024))&&Files.readString(root.resolve("stdin.sha")).equals(expected(3,1024)),"64MiB stdout+stderr+stdin digests at32m");c(process,"Close");check(owner.openResourceCount()==0,"large resources0");
        }
    }
    static void large(Path root) throws Exception {Path errors=root.resolve("large-errors.txt");Process child=new ProcessBuilder(JAVA,"-Xmx32m","-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"),"-cp",CP,"ProcessLibraryTests","--large",root.toString()).redirectError(errors.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();if(!child.waitFor(35,TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("large process test timeout");}check(child.exitValue()==0,Files.readString(errors));}
    static void byref(Path root) throws Exception {
        Path project=root.resolve("bsl");Files.createDirectory(project);String cmd="\""+JAVA+"\" -cp \""+CP+"\" ProcessLibraryTests --fixture exit";
        Path script=project.resolve("Программа.bsl");String literal="\""+cmd.replace("\"","\"\"")+"\"";
        Files.writeString(script,"""
            Функция Копия(Знач Код)
                RunApp(%s,,Истина,Код); Возврат Код;
            КонецФункции
            Код=99; ЗапуститьПриложение(%s,,Истина,Код);
            Если Код<>7 Тогда ВызватьИсключение "variable"; КонецЕсли;
            С=Новый Структура("Код",99); RunApp(%s,,Истина,С.Код);
            А=Новый Массив; А.Добавить(99); RunApp(%s,,Истина,А[0]);
            Вход=99; Выход=Копия(Вход);
            Если С.Код<>7 Или А[0]<>7 Или Вход<>99 Или Выход<>7 Тогда ВызватьИсключение "output refs/value local"; КонецЕсли;
            Консоль.ВывестиСтроку(Код); ЗавершитьРаботу(Код);
            """.formatted(literal,literal,literal,literal));
        Path jar=root.resolve("app.jar");for(boolean built:List.of(false,true)){ByteArrayOutputStream out=new ByteArrayOutputStream(),err=new ByteArrayOutputStream();if(built)check(Main.execute(new String[]{"build",script.toString(),"--output",jar.toString()},new PrintStream(out),new PrintStream(err))==0,err.toString());Process child=new ProcessBuilder(built?List.of(JAVA,"-jar",jar.toString()):List.of(JAVA,"-jar",Path.of("build/juno.jar").toAbsolutePath().toString(),"run",script.toString())).directory(root.toFile()).start();check(child.waitFor(10,TimeUnit.SECONDS),"BSL timeout");String stdout=new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8),stderr=new String(child.getErrorStream().readAllBytes(),StandardCharsets.UTF_8);check(child.exitValue()==7,"BSL output-param exit run/JAR: "+child.exitValue()+" stdout="+stdout+" stderr="+stderr);check(stdout.equals("7"+System.lineSeparator())&&stderr.isEmpty(),"BSL output bytes");}
    }
    static long spools(Path dir) throws IOException {try(var files=Files.list(dir)){return files.filter(f->f.getFileName().toString().startsWith("juno-process-")).count();}}
    /** Processes cannot be closed in 1C, so a finished one must give back its files and descriptors by itself. */
    static void unclosed(Path root) throws Exception {
        List<String> echo=System.getProperty("os.name").startsWith("Windows")?List.of("cmd.exe","/c","echo","hello"):List.of("/bin/echo","hello");
        Path temp=Path.of(System.getProperty("java.io.tmpdir"));long before=spools(temp);
        try(var owner=context(root,Map.of(),new ByteArrayOutputStream(),new ByteArrayOutputStream());var scope=Execution.withContext(owner)){
            for(int i=0;i<60;i++){
                Object process=create(echo,"",true,false);
                c(process,"Start");check(c(process,"WaitForExit").equals(true),"the child ends");
                if(i==0)Thread.sleep(500); // read after the process has let go of its spool
                Object out=p(process,"StdOut");
                check(((String)c(out,"Read")).strip().equals("hello"),"output of an ended child stays readable");
                check(c(out,"Read")==UNDEFINED,"then end of data");
            }
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while((owner.openResourceCount()>0||spools(temp)>before)&&System.nanoTime()<end)Thread.sleep(20);
            check(owner.openResourceCount()==0,"ended processes are not resources: "+owner.openResourceCount());
            check(spools(temp)==before,"no spool files of ended processes: "+spools(temp)+" vs "+before);
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length>0){if(args[0].equals("--fixture"))fixture(args[1],Arrays.copyOfRange(args,2,args.length));else largeChild(Path.of(args[1]));return;}
        if(!Boolean.getBoolean("juno.process.isolated")){
            Path isolated=Files.createTempDirectory("juno-process-isolated-");try{Process child=new ProcessBuilder(JAVA,"-Djuno.process.isolated=true","-Djava.io.tmpdir="+isolated,"-cp",CP,"ProcessLibraryTests").inheritIO().start();if(!child.waitFor(60,TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("process suite timeout");}check(child.exitValue()==0,"isolated process suite");}
            finally{try(var files=Files.walk(isolated)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}return;
        }
        Path root=Files.createTempDirectory("juno-process-tests-");try{parser();basics(root);unclosed(root);live(root);environment(root);timeoutStop(root);slowHost(root);blockedInput(root);cancellation(root);large(root);byref(root);}finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
        try(var files=Files.list(Path.of(System.getProperty("java.io.tmpdir")))){check(files.noneMatch(path->path.getFileName().toString().startsWith("juno-process-")&&(path.toString().endsWith(".stdout")||path.toString().endsWith(".stderr"))),"all private process spools deleted");}
        check(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.getName().startsWith("juno-process-")),"no process watcher leaks");System.out.println("Process library PASS");
    }
}
