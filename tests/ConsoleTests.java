import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import juno.Main;
import juno.runtime.*;
import juno.runtime.RuntimeSupport.Module;
import static juno.runtime.RuntimeSupport.*;

/** Real host streams, pipe bytes and independent scopes; no System.setIn/Out/Err. */
public final class ConsoleTests {
    static final java.nio.charset.Charset UTF8=StandardCharsets.UTF_8;
    static final String EOL=System.lineSeparator();
    static void check(boolean ok,Object why) { if(!ok) throw new AssertionError(why); }
    static void fails(Runnable action) { try { action.run(); } catch(RuntimeException expected) { return; } throw new AssertionError("Expected failure"); }
    static Object c(Object receiver,String name,Object...args) { return call(receiver,name,args); }
    static Object g(String name,Object...args) { return global(name,args); }
    static Object console() { return globalValue("console"); }
    static Object p(Object target,String name) { return read(property(target,name)); }
    static byte[] utf8(String text) { return text.getBytes(UTF8); }
    static byte[] concat(byte[] a,byte[] b) { byte[] r=Arrays.copyOf(a,a.length+b.length); System.arraycopy(b,0,r,a.length,b.length); return r; }
    static void bytes(byte[] actual,byte[] expected,Object why) { check(Arrays.equals(actual,expected),why+": "+HexFormat.of().formatHex(actual)); }
    static final class HostInput extends ByteArrayInputStream {
        boolean closed;
        HostInput(byte[] bytes) { super(bytes); }
        public void close() { closed=true; }
    }
    static final class HostOutput extends ByteArrayOutputStream {
        boolean closed;
        public void close() { closed=true; }
    }
    static ApplicationContext context(InputStream in,OutputStream out,OutputStream err) {
        return new ApplicationContext(Path.of(""),Map.of("TOKEN","before"),in,out,err);
    }
    static void linesAndRaw() {
        HostInput in=new HostInput(utf8("\ufeffЯ😀\r\n\nZ\r尾")); HostOutput out=new HostOutput(),err=new HostOutput();
        ApplicationContext owner=context(in,out,err);
        try(owner;var scope=Execution.withContext(owner)) {
            Object k=console();
            check(c(k,"ReadLine").equals("\ufeffЯ😀"),"BOM, supplementary and CRLF");
            Object raw=c(k,"OpenStandardInput"),buffer=construct("BinaryDataBuffer",new Object[]{number("2")});
            check(c(raw,"Read",buffer,number("0"),number("1")).equals(number("1")),"raw following CR");
            check(getIndex(buffer,number("0")).equals(number("10")),"CR's deferred LF remains raw byte");
            check(c(k,"ПрочитатьСтроку").equals(""),"empty vs EOF");
            check(c(k,"ReadLine").equals("Z"),"lone CR");
            check(c(k,"ReadLine").equals("尾"),"last unterminated line");
            check(c(k,"ReadLine")==UNDEFINED && c(k,"ReadLine")==UNDEFINED,"stable EOF");
            check(c(raw,"Read",buffer,number("0"),number("2")).equals(number("0")),"raw EOF");
            c(raw,"Close"); c(raw,"Close"); fails(() -> c(raw,"Read",buffer,number("0"),number("0")));
            Object output=c(k,"OpenStandardOutput"),error=c(k,"ОткрытьСтандартныйПотокОшибок");
            assign(index(buffer,number("0")),number("0")); assign(index(buffer,number("1")),number("255"));
            c(output,"Write",buffer,number("0"),number("2")); c(error,"Записать",buffer,number("0"),number("2"));
            fails(() -> c(output,"Seek",number("0"))); fails(() -> c(output,"Read",buffer,number("0"),number("1")));
            fails(() -> ValueCopy.snapshot(output)); fails(() -> ValueCopy.snapshot(k));
            c(k,"Write",UNDEFINED); c(k,"WriteLine",nullValue()); c(k,"Write","Привет");
            check(owner.openResourceCount()==2,"only open wrappers owned");
        }
        bytes(out.toByteArray(),concat(new byte[]{0,(byte)255},utf8(EOL+"Привет")),"raw+text exact UTF8");
        bytes(err.toByteArray(),new byte[]{0,(byte)255},"stderr raw");
        check(!in.closed && !out.closed && !err.closed && owner.openResourceCount()==0,"host streams borrowed");
        fails(() -> owner.console());
    }
    static void immediateCr() throws Exception {
        // A second read would hang on a terminal with only CR available.
        AtomicInput in=new AtomicInput(); HostOutput out=new HostOutput();
        try(var owner=context(in,out,out);var scope=Execution.withContext(owner)) { check(c(console(),"ReadLine").equals("A"),"CR returns without lookahead"); }
        check(in.calls==2,"no read beyond CR");
    }
    static final class AtomicInput extends InputStream {
        int calls;
        public int read() { return switch(calls++) { case 0 -> 'A'; case 1 -> '\r'; default -> throw new AssertionError("read beyond CR"); }; }
    }
    static void encodingAndContexts() {
        HostOutput one=new HostOutput(),two=new HostOutput();
        try(var first=context(new HostInput("Привет\n".getBytes(java.nio.charset.Charset.forName("windows-1251"))),one,one);var scope=Execution.withContext(first)) {
            Object k=console(); assign(property(k,"InputEncoding"),"windows-1251");
            check(c(k,"ReadLine").equals("Привет"),"1251 stdin");
            assign(property(k,"OutputEncoding"),"windows-1251"); c(k,"WriteLine","Привет");
            try(var second=context(new HostInput(utf8("二\n")),two,two);var nested=Execution.withContext(second)) {
                check(p(console(),"OutputEncoding").equals(p(globalValue("textencoding"),"UTF8NoBOM")),"independent codec");
                check(c(console(),"ReadLine").equals("二"),"independent input"); c(console(),"WriteLine","二");
                check(g("GetEnvironmentVariable","TOKEN").equals("before"),"independent env"); g("SetEnvironmentVariable","TOKEN","second");
            }
            check(g("GetEnvironmentVariable","TOKEN").equals("before"),"nested context env isolation");
            check(System.getenv("TOKEN")==null || !System.getenv("TOKEN").equals("second"),"no OS mutation");
            fails(() -> assign(property(k,"OutputEncoding"),"ISO-2022-JP"));
            fails(() -> assign(property(k,"InputEncoding"),"bad-encoding"));
            fails(() -> g("GetEnvironmentVariable","TOKEN",p(globalValue("environmentvariabletarget"),"User")));
            fails(() -> g("GetEnvironmentVariable","TOKEN","Process"));
            fails(() -> g("SetEnvironmentVariable","A=B","x"));
            g("SetEnvironmentVariable","PathToken","x");
            check(g("GetEnvironmentVariable","PATHTOKEN").equals(System.getProperty("os.name").startsWith("Windows") ? "x" : UNDEFINED),"OS-aware name case");
        }
        bytes(one.toByteArray(),("Привет"+EOL).getBytes(java.nio.charset.Charset.forName("windows-1251")),"no BOM cp1251");
        bytes(two.toByteArray(),utf8("二"+EOL),"independent UTF8");
        for(byte[] input:List.of(new byte[]{(byte)255,10},new byte[]{(byte)0xd0})) {
            try(var owner=context(new HostInput(input),one,one);var scope=Execution.withContext(owner)) { fails(() -> c(console(),"ReadLine")); }
        }
        byte[] utf16="\ufeffЮ😀\n".getBytes(StandardCharsets.UTF_16LE);
        try(var owner=context(new HostInput(utf16),one,one);var scope=Execution.withContext(owner)) {
            assign(property(console(),"InputEncoding"),"UTF-16LE"); check(c(console(),"ReadLine").equals("\ufeffЮ😀"),"UTF16LE BOM preserved");
        }
        HostOutput surrogate=new HostOutput();
        try(var owner=context(new HostInput(new byte[0]),surrogate,surrogate);var scope=Execution.withContext(owner)){c(console(),"Write","a\ud800b");}
        bytes(surrogate.toByteArray(),utf8("a\ufffdb"),"isolated UTF16 is replaced in stdout");
        // PrintStream otherwise swallows IOException: a broken pipe must fail the script.
        PrintStream broken=new PrintStream(new OutputStream() { public void write(int b) throws IOException { throw new IOException("broken pipe"); } });
        try(var owner=context(InputStream.nullInputStream(),broken,one);var scope=Execution.withContext(owner)) { fails(() -> c(console(),"Write","x")); }
    }
    static CountDownLatch entered,gate;
    public static final class Worker implements Module {
        public Worker() {}
        public String __juno_metadata() { return "M\t0\tinput\t0\nM\t0\tsnapshot\t0\nM\t0\traw\t0\n"; }
        public Object __juno_get(int n) { throw new AssertionError(); }
        public void __juno_set(int n,Object v) { throw new AssertionError(); }
        public Object __juno_call(int n,Object a,Object b,Object d) { return __juno_invoke(n,new Object[0]); }
        public Object __juno_invoke(int n,Object[] args) {
            if(n==0) return c(console(),"ReadLine");
            if(n==2) return c(console(),"OpenStandardInput");
            entered.countDown();
            try { gate.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); Execution.checkCancellation(); }
            check(g("GetEnvironmentVariable","TOKEN").equals("before"),"job environment at submission");
            check(p(console(),"OutputEncoding").equals(p(globalValue("textencoding"),"ANSI")),"job codec at submission");
            assign(property(console(),"OutputEncoding"),"UTF8"); g("SetEnvironmentVariable","TOKEN","child"); return UNDEFINED;
        }
    }
    static InputStream forbiddenInput() { return new InputStream() { public int read() { throw new AssertionError("worker/lint must never touch host stdin"); } }; }
    static void workers() throws Exception {
        HostOutput out=new HostOutput();
        try(var app=new BackgroundJobs(Map.of("Worker",Worker.class),1,4,s -> {},context(forbiddenInput(),out,out))) {
            app.run(() -> {
                Object k=console(); assign(property(k,"OutputEncoding"),"windows-1251");
                entered=new CountDownLatch(1); gate=new CountDownLatch(1);
                BackgroundJobs.Job snapshot=app.execute("Worker.snapshot",UNDEFINED,UNDEFINED,"");
                check(entered.await(2,TimeUnit.SECONDS),"snapshot job entered");
                assign(property(k,"OutputEncoding"),"UTF8"); g("SetEnvironmentVariable","TOKEN","parent"); gate.countDown(); snapshot.result();
                check(g("GetEnvironmentVariable","TOKEN").equals("parent"),"child cannot mutate parent environment");
                for(String method:List.of("Worker.input","Worker.raw")) {
                    BackgroundJobs.Job job=app.execute(method,UNDEFINED,UNDEFINED,"");
                    check(job.await(number("2")),"stdin rejected without blocking"); fails(job::result);
                }
                return UNDEFINED;
            });
        }
    }
    static Path script(Path tmp,String name,String source) throws IOException {
        Path dir=Files.createDirectories(tmp.resolve(name)); return Files.writeString(dir.resolve("Main.bsl"),source);
    }
    record Result(int code,byte[] out,byte[] err) {}
    static Result execute(String[] args,byte[] input) {
        HostOutput out=new HostOutput(),err=new HostOutput(); HostInput in=new HostInput(input);
        int code=Main.execute(args,in,new PrintStream(out,true,UTF8),new PrintStream(err,true,UTF8));
        check(!in.closed && !out.closed && !err.closed,"Main borrows all streams"); return new Result(code,out.toByteArray(),err.toByteArray());
    }
    static void lint(Path tmp) throws Exception {
        Path sources=Files.createDirectories(tmp.resolve("lint-source")); Files.writeString(sources.resolve("A.bsl"),"Процедура А() КонецПроцедуры");
        Files.writeString(sources.resolve("B.bsl"),"Процедура Б() КонецПроцедуры");
        Path plugin=tmp.resolve("ConsolePlugin.bsl"); Files.writeString(plugin,"""
            Процедура Открыть(Парсер,Параметры) Экспорт
                Консоль.ПрочитатьСтроку();
            КонецПроцедуры
            Функция Подписки() Экспорт Возврат Новый Массив; КонецФункции
            """);
        for(String jobs:List.of("1","4")) {
            HostOutput out=new HostOutput(),err=new HostOutput();
            int code=Main.execute(new String[]{"lint",sources.toString(),"--plugin",plugin.toString(),"--jobs",jobs},forbiddenInput(),new PrintStream(out,true,UTF8),new PrintStream(err,true,UTF8));
            check(code!=0 && (out.toString(UTF8)+err.toString(UTF8)).contains("stdin"),"lint stdin error jobs="+jobs+" "+out+err);
        }
    }
    static Result child(List<String> args,Path tmp,Path input) throws Exception {
        List<String> command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString())); command.addAll(args);
        Path out=tmp.resolve("child.out"),err=tmp.resolve("child.err");
        Process process=new ProcessBuilder(command).redirectInput(input.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        if(!process.waitFor(20,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("child timed out: "+args); }
        return new Result(process.exitValue(),Files.readAllBytes(out),Files.readAllBytes(err));
    }
    static void pipelines(Path tmp) throws Exception {
        String filter="""
            Пока Истина Цикл
                СтрокаВвода = Консоль.ПрочитатьСтроку();
                Если СтрокаВвода = Неопределено Тогда Прервать; КонецЕсли;
                Консоль.ВывестиСтроку("["+СтрокаВвода+"]");
            КонецЦикла;
            E=Консоль.ОткрытьСтандартныйПотокОшибок(); B=Новый БуферДвоичныхДанных(2);
            B[0]=69; B[1]=10; E.Записать(B,0,2); E.Закрыть(); ЗавершитьРаботу(7);
            """;
        Path source=script(tmp,"filter",filter),input=Files.write(tmp.resolve("input.bin"),utf8("Привет\r\n\n尾"));
        Result host=execute(new String[]{"run",source.toString()},Files.readAllBytes(input));
        check(host.code==7,"host exit"); bytes(host.out,utf8("[Привет]"+EOL+"[]"+EOL+"[尾]"+EOL),"host filter"); bytes(host.err,utf8("E\n"),"host stderr");
        Path jar=Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()),built=tmp.resolve("filter.jar");
        check(execute(new String[]{"build",source.toString(),"--output",built.toString()},new byte[0]).code==0,"build filter");
        for(List<String> args:List.of(List.of("-jar",jar.toString(),"run",source.toString()),List.of("-jar",built.toString()))) {
            Result run=child(args,tmp,input); check(run.code==7,"subprocess exit "+new String(run.err,UTF8)); bytes(run.out,host.out,"redirected UTF8 stdout"); bytes(run.err,host.err,"redirected stderr");
        }
        Path enc=script(tmp,"enc","Console.OutputEncoding=TextEncoding.ANSI; Console.WriteLine(\"Привет\"); Сообщить(\"Мир\");");
        bytes(execute(new String[]{"run",enc.toString()},new byte[0]).out,("Привет"+EOL+"Мир"+EOL).getBytes(java.nio.charset.Charset.forName("windows-1251")),"Console+Message codec");
        Path fail=script(tmp,"failure","Console.Write(\"ok\"); ВызватьИсключение \"ошибка\";");
        Result failure=execute(new String[]{"run",fail.toString()},new byte[0]);
        check(failure.code==3 && new String(failure.err,UTF8).contains("ошибка"),"diagnostic exit3"); bytes(failure.out,utf8("ok"),"diagnostics separated");
        check(execute(new String[]{"build",fail.toString(),"--output",built.toString()},new byte[0]).code==0,"build failure");
        Result builtFailure=child(List.of("-jar",built.toString()),tmp,input);
        check(builtFailure.code==3 && new String(builtFailure.err,UTF8).contains("ошибка"),"built diagnostic exit3"); bytes(builtFailure.out,utf8("ok"),"built diagnostics separated");
        PrintStream broken=new PrintStream(new OutputStream() { public void write(int b) throws IOException { throw new IOException("pipe"); } });
        HostOutput err=new HostOutput(); check(Main.execute(new String[]{"run",fail.toString()},InputStream.nullInputStream(),broken,new PrintStream(err,true,UTF8))==3,"Main broken pipe failure");
        binaryPipeline(tmp,jar);
    }
    static void binaryPipeline(Path tmp,Path jar) throws Exception {
        Path source=script(tmp,"binary","""
            I=Console.OpenStandardInput(); O=Console.OpenStandardOutput(); B=Новый BinaryDataBuffer(32768);
            Пока Истина Цикл
                N=I.Read(B,0,32768); Если N=0 Тогда Прервать; КонецЕсли;
                O.Write(B,0,N);
            КонецЦикла;
            I.Close(); O.Close();
            """);
        Path input=tmp.resolve("large.bin"),output=tmp.resolve("large-copy.bin"),error=tmp.resolve("large.err"); byte[] block=new byte[65536]; new Random(17).nextBytes(block);
        try(OutputStream out=Files.newOutputStream(input)) { for(int i=0;i<1024;i++) out.write(block); out.write(new byte[]{0,(byte)255,13}); }
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx32m","-jar",jar.toString(),"run",source.toString()).redirectInput(input.toFile()).redirectOutput(output.toFile()).redirectError(error.toFile()).start();
        if(!process.waitFor(25,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("binary pipeline timeout"); }
        check(process.exitValue()==0,Files.readString(error)); check(Files.size(error)==0 && Files.mismatch(input,output)==-1,"64 MiB+3 raw pipeline under 32 MiB heap, no BOM/newline changes");
    }
    public static void main(String[] args) throws Exception {
        Path tmp=Files.createTempDirectory("juno-console-tests-");
        try { linesAndRaw(); immediateCr(); encodingAndContexts(); workers(); lint(tmp); pipelines(tmp); }
        finally { try(var files=Files.walk(tmp)) { for(Path path:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
        System.out.println("Console library PASS");
    }
}
