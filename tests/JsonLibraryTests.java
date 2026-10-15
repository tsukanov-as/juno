import java.io.*;
import java.math.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import juno.runtime.*;
import juno.runtime.RuntimeSupport.Module;
import static juno.runtime.RuntimeSupport.*;

/** Public JSON precision, strict errors, cursor, fixed collections and file ownership. */
public final class JsonLibraryTests {
    static final BigDecimal ZERO=BigDecimal.ZERO,ONE=BigDecimal.ONE;
    static void check(boolean ok,Object why) { if(!ok) throw new AssertionError(why); }
    static RuntimeException fails(Runnable action) { try { action.run(); } catch(RuntimeException e) { return e; } throw new AssertionError("Expected error"); }
    static Object c(Object object,String name,Object...args) { return call(object,name,args); }
    static Object g(String name,Object...args) { return global(name,args); }
    static Object p(Object object,String name) { return read(property(object,name)); }
    static Object e(String namespace,String member) { return p(globalValue(namespace),member); }
    static Object n(int value) { return number(Integer.toString(value)); }
    static Object make(String name,Object...args) { return construct(name,args); }
    static ApplicationContext context(Path path) { return new ApplicationContext(path,Map.of(),InputStream.nullInputStream(),OutputStream.nullOutputStream(),OutputStream.nullOutputStream()); }
    static Object reader(String text) { Object r=make("JSONReader"); c(r,"SetString",text); return r; }
    static Object parse(String text,boolean map) { return g("ReadJSON",reader(text),map); }
    static Object writer() { Object w=make("JSONWriter"); c(w,"SetString",make("JSONWriterSettings",e("jsonlinebreak","None"))); return w; }
    static String stringify(Object value) { Object w=writer(); g("WriteJSON",w,value); return (String)c(w,"Close"); }
    static FileChannel channel(Object resource) throws Exception { var f=resource.getClass().getDeclaredField("channel"); f.setAccessible(true); return (FileChannel)f.get(resource); }
    static String write(Object value,Object settings) { Object w=writer(); g("WriteJSON",w,value,settings); return (String)c(w,"Close"); }
    static String quoted(String text) { return "\""+text+"\""; }
    static LocalDateTime readDate(String text,Object format) { return (LocalDateTime)g("ReadJSONDate",text,format); }
    /** Expected texts were produced by 1C 8.3.27.1606 in the Europe/Moscow zone (UTC+3, no daylight saving). */
    static void dates() {
        TimeZone saved=TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"));
        try {
            Object iso=e("jsondateformat","ISO"),js=e("jsondateformat","JavaScript"),ms=e("jsondateformat","Microsoft");
            Object local=e("jsondatewritingvariant","LocalDate"),offset=e("jsondatewritingvariant","LocalDateWithOffset"),utc=e("jsondatewritingvariant","UniversalDate");
            LocalDateTime date=LocalDateTime.of(2024,2,29,10,0,5),first=LocalDateTime.of(1,1,1,0,0,0);
            check(stringify(date).equals(quoted("2024-02-29T10:00:05")),"WriteJSON writes a date as local ISO");
            check(stringify(first).equals(quoted("0001-01-01T00:00:00")),"the empty date");
            Object list=make("Array"); c(list,"Add",date); c(list,"Add",first);
            check(stringify(list).equals("[\"2024-02-29T10:00:05\",\"0001-01-01T00:00:00\"]"),"dates inside a collection");
            check(g("WriteJSONDate",date,iso).equals("2024-02-29T10:00:05") && g("WriteJSONDate",date,iso,local).equals("2024-02-29T10:00:05"),"local ISO");
            check(g("WriteJSONDate",date,iso,offset).equals("2024-02-29T10:00:05+03:00"),"local ISO with the offset");
            check(g("WriteJSONDate",date,iso,utc).equals("2024-02-29T07:00:05Z"),"universal ISO");
            check(g("WriteJSONDate",date,js,utc).equals("new Date(1709190005000)"),"universal JavaScript");
            check(g("WriteJSONDate",date,ms,utc).equals("/Date(1709190005000)/"),"universal Microsoft");
            for(Object format:List.of(js,ms)) for(Object variant:List.of(local,offset)) {
                RuntimeException failure=fails(() -> g("WriteJSONDate",date,format,variant));
                check(failure.getMessage().startsWith("Невозможно сохранить локальную дату в формате "),failure.getMessage());
            }
            fails(() -> g("WriteJSONDate",date,js)); fails(() -> g("WriteJSONDate",date.withNano(1),iso)); fails(() -> stringify(date.withNano(1)));
            check(g("WriteJSONDate",first,iso,utc).equals("0001-01-01T00:00:00Z") && g("WriteJSONDate",first,js,utc).equals("new Date(-62135596800000)"),"the empty date is not shifted");
            check(g("WriteJSONDate",first,iso,offset).equals("0001-01-01T00:00:00+00:00"),"the empty date has a zero offset");
            check(g("WriteJSONDate",LocalDateTime.of(9999,12,31,23,59,59),iso,utc).equals("9999-12-31T20:59:59Z"),"the last date");
            Object settings=make("JSONSerializerSettings");
            check(p(settings,"DateSerializationFormat")==iso && p(settings,"DateWritingVariant")==local,"default serializer settings");
            assign(property(settings,"DateWritingVariant"),utc); assign(property(settings,"DateSerializationFormat"),ms);
            check(write(date,settings).equals(quoted("/Date(1709190005000)/")),"serializer settings choose the format and the variant");
            fails(() -> assign(property(settings,"DateWritingVariant"),iso)); fails(() -> assign(property(settings,"Unknown"),iso)); fails(() -> write(date,"settings"));
            // Texts read by 1C: the zone is applied, the fraction is dropped, forms that the platform accepts or refuses.
            LocalDateTime ten=LocalDateTime.of(2024,2,29,10,0,0),thirteen=ten.withHour(13);
            for(String text:List.of("2024-02-29T10:00:00","2024-02-29T10:00:00+03:00","2024-02-29T10:00:00+03","2024-02-29T10:00:00.123","2024-02-29T10:00:00.123456","2024-02-29T10:00:00 +03:00","20240229T100000","2024-02-29T10:00:00+0300","2024-02-29T10","2024-02-29T10:00"," 2024-02-29T10:00:00"))
                check(readDate(text,iso).equals(ten),text);
            for(String text:List.of("2024-02-29T10:00:00Z","2024-02-29T10:00:00.123Z","2024-02-29T10:00:00Z ","2024-02-29T10:00:00-00:00")) check(readDate(text,iso).equals(thirteen),text);
            check(readDate("2024-02-29T10:00:00-05:00",iso).equals(ten.withHour(18)),"negative offset");
            check(readDate("2024-02-29T10:00:00+14:00",iso).equals(LocalDateTime.of(2024,2,28,23,0,0)) && readDate("2024-02-29T10:00:00+24:00",iso).equals(LocalDateTime.of(2024,2,28,13,0,0)),"large offsets");
            check(readDate("2024-02-29",iso).equals(ten.withHour(0)) && readDate("0001-01-01T00:00:00",iso).equals(first),"date only and the empty date");
            check(readDate("0001-01-01T00:00:00Z",iso).equals(LocalDateTime.of(1,1,1,2,30,17)) && readDate("9999-12-31T23:59:59Z",iso).equals(LocalDateTime.of(9999,12,31,23,59,59)),"range edges");
            for(String bad:List.of("2024-02-29 10:00:00","bad","","2024-02-29t10:00:00z","0000-01-01T00:00:00","2024-13-01T00:00:00","2024-02-30T00:00:00","2023-02-29T10:00:00","2024-02-29T24:00:00","2024-02-29T10:00:60",
                "2024-2-9T1:2:3","24-02-29T10:00:00","2024-02-29T10:00:00.","2024-02-29T10:00:00.Z","2024-02-29Z","2024-02-29T10:00Z","2024-02-29T10:00:00,5","12024-02-29T10:00:00","2024-02-29T10:00:00+3:00")) {
                RuntimeException failure=fails(() -> readDate(bad,iso));
                check(failure.getMessage().equals("Представление даты имеет неверный формат ('"+bad+"')"),failure.getMessage());
            }
            check(readDate("new Date(1709190005000)",js).equals(date) && readDate("new Date(-62135596800000)",js).equals(LocalDateTime.of(1,1,1,2,30,17)),"JavaScript");
            check(readDate("/Date(1709200800000)/",ms).equals(thirteen) && readDate("/Date(1709200800999)/",ms).equals(thirteen),"Microsoft");
            for(String bad:List.of("2024-02-29T10:00:00.000Z","/Date(1709200800000+0300)/","new Date(x)")) fails(() -> readDate(bad,js));
            fails(() -> readDate("2024-02-29T10:00:00",ms)); fails(() -> readDate("/Date(1709200800000+0300)/",ms)); fails(() -> g("ReadJSONDate","2024-02-29T10:00:00"));
            fails(() -> g("ReadJSONDate",ONE,iso));
            Object names=make("Array"); c(names,"Add","d"); Object r=reader("{\"d\":\"2024-02-29T10:00:00Z\",\"s\":\"2024-02-29T10:00:00\"}");
            Object read=g("ReadJSON",r,false,names);
            check(p(read,"d").equals(thirteen) && p(read,"s").equals("2024-02-29T10:00:00"),"listed properties are dates, the format defaults to ISO");
            check(p(g("ReadJSON",reader("{\"d\":\"2024-02-29T10:00:00\"}"),false,"d"),"d").equals(ten),"one property name as a string");
            check(p(g("ReadJSON",reader("{\"d\":\"/Date(1709200800000)/\"}"),false,"d",ms),"d").equals(thirteen),"format of the listed properties");
            check(p(g("ReadJSON",reader("{\"d\":null}"),false,"d"),"d")==Special.NULL,"Null stays Null");
            fails(() -> g("ReadJSON",reader("{\"d\":\"\"}"),false,"d")); fails(() -> g("ReadJSON",reader("{\"d\":\"zzz\"}"),false,"d")); fails(() -> g("ReadJSON",reader("{}"),false,UNDEFINED,iso));
        } finally { TimeZone.setDefault(saved); }
    }
    static void values(Path tmp) throws Exception {
        try(var owner=context(tmp);var scope=Execution.withContext(owner)) {
            String big="123456789012345678901234567890.1234567890123456789012345";
            check(parse(big,false).equals(new BigDecimal(big)),"decimal precision independent of OS/.NET double");
            for(String value:List.of("-0.001","1e200","1e-200","true","false","null","\"ё😀\\n\\uD83D\\uDE00\"","{}","[]","[null,[1],{\"a\":2}]")) {
                String encoded=stringify(parse(value,true)); check(encoded.equals(stringify(parse(encoded,true))),"stable semantic round trip "+value);
            }
            check(parse("null",false)==nullValue(),"language Null distinct from Undefined");
            fails(() -> juno.Json.parse("{/*public-only comment*/\"a\":1}"));
            check(p(parse("{\"a\":1,\"A\":2}",false),"a").equals(n(2)),"Struct case-insensitive last value");
            check(c(parse("{\"a-b\":1,\"a-b\":2}",true),"Get","a-b").equals(n(2)),"Map arbitrary key, duplicate last wins");
            check(p(parse("{\"é\":1,\"Истина\":2,\"_\":3}//tail",false),"é").equals(ONE),"Unicode identifier/keyword/tail comments");
            for(String key:List.of("", "a-b", "1a")) fails(() -> parse("{\""+key+"\":1}",false));
            Object reused=reader("{}\n //tail"); g("ReadJSON",reused);
            check(p(reused,"CurrentValueType").equals(e("jsonvaluetype","ObjectEnd")) && p(reused,"CurrentPosition").equals(n(2)) && p(reused,"CurrentLine").equals(ONE),"logical token position retained after tail validation");
            check(c(reused,"Read").equals(false) && p(reused,"CurrentLine").equals(n(2)),"next EOF uses physical position");
            c(reused,"SetString","[1]"); c(reused,"Read"); check(g("ReadJSON",reused).equals(ONE),"ReadJSON advances within array"); c(reused,"Close");
            Object map=make("Map"); c(map,"Insert",ONE,"a"); fails(() -> stringify(map));
            c(map,"Insert","1","b"); fails(() -> stringify(map));
            Object child=make("Array"); c(child,"Add",ONE); Object shared=make("Array"); c(shared,"Add",child); c(shared,"Add",child);
            check(stringify(shared).equals("[[1],[1]]"),"shared alias allowed without cycle"); c(child,"Add",shared); fails(() -> stringify(shared));
            fails(() -> stringify(make("ValueTable"))); fails(() -> stringify(type("Number")));
            fails(() -> stringify("\uD800")); fails(() -> stringify("\uDC00"));
            dates();
            Object settings=make("JSONWriterSettings",e("jsonlinebreak","None"),"",true,e("jsoncharactersescapemode","NotASCIISymbols"),true,true,true,true,true);
            check(p(settings,"EscapeLineTerminators").equals(true),"EN settings getter");
            Object escaped=make("JSONWriter"); c(escaped,"SetString",settings); c(escaped,"WriteValue","<>&'/\u2028ё😀");
            String escapedText=(String)c(escaped,"Close"); check(escapedText.equals("\"\\u003c\\u003e\\u0026\\u0027\\/\\u2028\\u0451\\ud83d\\ude00\""),"explicit escaping exact bytes");
            check(ValueCopy.snapshot(settings)==settings,"immutable settings transport");
            Object bmp=make("JSONWriter"); c(bmp,"SetString",make("JSONWriterSettings",e("jsonlinebreak","None"),"",true,e("jsoncharactersescapemode","SymbolsNotInBMP")));
            c(bmp,"WriteValue","ё😀"); check(c(bmp,"Close").equals("\"ё\\ud83d\\ude00\""),"BMP escape keeps BMP characters");
            check(owner.openResourceCount()==0,"globals release string readers/writers on success/error");
        }
    }
    static void negative(Path tmp) {
        List<String> invalid=List.of("", "/*only*/", "[", "{", "[1,]", "{\"a\":1,}", "[1 2]", "{\"a\" 1}", "{\"a\":}", "{a:1}", "['x']", "01", "+1", ".1", "1.", "1e", "--1", "NaN", "Infinity", "trueX", "null false", "{}garbage", "/*", "\"\\q\"", "\"\\u000x\"", "\"\\uD800\"", "\"\\uDC00\"", "\"\\uD800x\"", "\"a\nb\"", "[1}\n", "1e2147483647", "1e100001");
        try(var owner=context(tmp);var scope=Execution.withContext(owner)) {
            for(String source:invalid) {
                RuntimeException error=fails(() -> parse(source,true)); check(error.getMessage().contains("позици"),"position for "+source+": "+error); check(owner.openResourceCount()==0,"error releases reader");
            }
            fails(() -> parse("[".repeat(257)+"]".repeat(257),true));
            fails(() -> parse("\""+"a".repeat(1_048_577)+"\"",true));
            Object w=writer(); fails(() -> c(w,"WriteRaw","1 2")); check(owner.openResourceCount()==0,"raw error releases writer");
            Object comment=writer(); fails(() -> c(comment,"WriteRaw","/*x*/1"));
            Object state=writer(); c(state,"WriteStartObject"); fails(() -> c(state,"WriteValue",ONE));
            Object name=writer(); fails(() -> c(name,"WritePropertyName","a"));
            Object end=writer(); fails(() -> c(end,"WriteEndArray"));
            Object root=writer(); c(root,"WriteValue",ONE); fails(() -> c(root,"WriteValue",ONE));
            Object unclosed=writer(); c(unclosed,"WriteStartArray"); fails(() -> c(unclosed,"Close")); check(c(unclosed,"Close").equals(""),"close after failure idempotent");
            Object closed=writer(); c(closed,"WriteValue",ONE); c(closed,"Close"); fails(() -> c(closed,"WriteValue",ONE));
            Object validate=writer(); fails(() -> assign(property(validate,"ValidateStructure"),false)); c(validate,"WriteValue",ONE); c(validate,"Close");
            fails(() -> make("JSONWriterSettings",e("jsonlinebreak","None"),"",false));
            fails(() -> make("JSONWriterSettings",e("jsonlinebreak","Unix"),"bad"));
        }
    }
    static void fixed(Path tmp) {
        try(var owner=context(tmp);var scope=Execution.withContext(owner)) {
            Object array=make("Array"); c(array,"Add",ONE); Object fixed=make("FixedArray",array);
            Object map=make("Map"); c(map,"Insert","a",ONE); Object fixedMap=make("FixedMap",map);
            Object structure=make("FixedStructure",make("Structure","a",ONE));
            Object key=n(0);
            fails(() -> assign(index(fixed,key),n(2))); fails(() -> writeIndex(fixed,prepareIndex(fixed,key),n(2)));
            fails(() -> assign(index(fixedMap,"a"),n(2))); fails(() -> writeIndex(fixedMap,prepareIndex(fixedMap,"a"),n(2)));
            fails(() -> assign(property(structure,"a"),n(2))); fails(() -> writeProperty(structure,prepareProperty(structure,"a"),n(2)));
            for(String method:List.of("add","insert","clear","delete","set")) {
                MethodSite site=new MethodSite(method);
                // Warm mutable target before fixed receiver of the same Java class.
                Object mutable=make("Array"); c(mutable,"Add",ONE);
                if(method.equals("add") || method.equals("delete")) { site.call1(mutable,key); fails(() -> site.call1(fixed,key)); }
                else if(method.equals("clear")) { site.call0(mutable); fails(() -> site.call0(fixed)); }
                else { site.call2(mutable,key,ONE); fails(() -> site.call2(fixed,key,ONE)); }
                fails(() -> c(fixed,method,key,ONE));
            }
            for(String method:List.of("insert","clear","delete")) {
                MethodSite site=new MethodSite(method); Object mutable=make("Map"); c(mutable,"Insert","a",ONE);
                if(method.equals("clear")) { site.call0(mutable); fails(() -> site.call0(fixedMap)); }
                else if(method.equals("delete")) { site.call1(mutable,"a"); fails(() -> site.call1(fixedMap,"a")); }
                else { site.call2(mutable,"a",ONE); fails(() -> site.call2(fixedMap,"a",ONE)); }
                fails(() -> c(fixedMap,method,"a",ONE));
            }
            Iterator<?> it=(Iterator<?>)iterator(fixed); it.next(); fails(it::remove);
            Object copy=ValueCopy.snapshot(fixed); check(valueType(copy).equals(type("FixedArray")),"transport fixed type"); fails(() -> c(copy,"Add",ONE));
            Object base=make("Array"); c(base,"Add",base); Object wrapper=make("FixedArray",base); Object graph=make("Array"); c(graph,"Add",wrapper); c(graph,"Add",wrapper);
            Object cloned=ValueCopy.snapshot(graph),a=getIndex(cloned,ZERO); check(a==getIndex(cloned,ONE),"alias through fixed preserved");
            Object nested=getIndex(a,ZERO); check(nested==getIndex(nested,ZERO),"cycle through fixed preserved"); check(valueType(a).equals(type("FixedArray")),"fixed in graph");
            Object fixedMapCopy=ValueCopy.snapshot(fixedMap); fails(() -> c(fixedMapCopy,"Insert","a",n(2)));
            check(c(fixedMapCopy,"Get","a").equals(ONE),"fixed map copy readable");
            fails(() -> make("FixedArray")); fails(() -> make("FixedMap")); fails(() -> make("FixedArray",fixed)); fails(() -> make("Map",map));
        }
    }
    static void files(Path tmp) throws Exception {
        Path path=tmp.resolve("file.json");
        try(var owner=context(tmp);var scope=Execution.withContext(owner)) {
            Object w=make("JSONWriter"); c(w,"OpenFile","file.json"); c(w,"WriteValue","Я"); FileChannel file=channel(w); c(w,"Close"); check(!file.isOpen(),"explicit writer handle closed");
            check(Arrays.equals(Files.readAllBytes(path),"\"Я\"".getBytes(StandardCharsets.UTF_8)),"JSON default UTF8 no BOM");
            c(w,"OpenFile","file.json","UTF-8",true); c(w,"WriteValue",ONE); c(w,"Close"); check(HexFormat.of().formatHex(Files.readAllBytes(path)).equals("efbbbf31"),"explicit UTF8 BOM");
            Object r=make("JSONReader"); c(r,"OpenFile","file.json"); FileChannel readChannel=channel(r); check(g("ReadJSON",r).equals(ONE),"BOM skipped"); check(!readChannel.isOpen() && owner.openResourceCount()==0,"root read releases file");
            c(w,"OpenFile","file.json","UTF-16LE",true); c(w,"WriteValue","Я"); c(w,"Close");
            check(HexFormat.of().formatHex(Files.readAllBytes(path)).equals("fffe22002f042200"),"UTF16LE exact bytes");
            c(r,"OpenFile","file.json"); check(g("ReadJSON",r).equals("Я"),"UTF16 BOM detected");
            Files.writeString(path,"[1,]"); c(r,"OpenFile","file.json"); FileChannel error=channel(r); fails(() -> g("ReadJSON",r)); check(!error.isOpen() && owner.openResourceCount()==0,"parse failure closes file");
            Files.write(path,new byte[]{(byte)255}); c(r,"OpenFile","file.json"); fails(() -> c(r,"Read")); check(owner.openResourceCount()==0,"strict invalid UTF8 cleanup");
            Path directory=Files.createDirectories(tmp.resolve("directory")); fails(() -> c(r,"OpenFile",directory.toString())); fails(() -> c(w,"OpenFile",directory.toString()));
            Object owned=writer(); fails(() -> ValueCopy.snapshot(owned)); c(owned,"WriteValue",ONE); c(owned,"Close");
        }
        // Context.finish marks owner closed before Resource.close: no requireOpen in cleanup.
        for(boolean complete:List.of(true,false)) {
            ApplicationContext owner=context(tmp); FileChannel file;
            try(var scope=Execution.withContext(owner)) {
                Object w=make("JSONWriter"); c(w,"OpenFile","file.json"); file=channel(w);
                if(complete) c(w,"WriteValue","flush on scope exit"); else c(w,"WriteStartArray");
            }
            if(complete) owner.close(); else fails(owner::close);
            check(owner.openResourceCount()==0 && !file.isOpen(),"scope cleanup complete="+complete);
            if(complete) check(Files.readString(path).equals("\"flush on scope exit\""),"scope flush"); Files.delete(path);
        }
        ApplicationContext owner=context(tmp); FileChannel failed;
        try(var scope=Execution.withContext(owner)) {
            Object w=make("JSONWriter"); c(w,"OpenFile","file.json"); failed=channel(w); fails(() -> g("WriteJSON",w,make("ValueTable")));
        }
        owner.close(); check(!failed.isOpen() && owner.openResourceCount()==0,"serializer error actual handle release"); Files.delete(path);
        ApplicationContext interrupted=context(tmp); FileChannel interruptedFile;
        try(var scope=Execution.withContext(interrupted)) {
            Object w=make("JSONWriter"); c(w,"OpenFile","file.json","US-ASCII"); interruptedFile=channel(w); c(w,"WriteValue","Я");
        }
        Thread.currentThread().interrupt();
        try { interrupted.close(); throw new AssertionError("Expected cancellation on failed encoder flush"); }
        catch(Execution.Cancelled expected) { }
        finally { Thread.interrupted(); }
        check(!interruptedFile.isOpen() && interrupted.openResourceCount()==0,"Cancelled Error during flush still closes channel"); Files.delete(path);
    }
    static CountDownLatch entered;
    static volatile ApplicationContext jobOwner;
    static volatile FileChannel jobReader,jobWriter;
    public static final class Worker implements Module {
        public Worker() {}
        public String __juno_metadata() { return "M\t0\tblock\t0\n"; }
        public Object __juno_get(int n) { throw new AssertionError(); }
        public void __juno_set(int n,Object v) { throw new AssertionError(); }
        public Object __juno_call(int n,Object a,Object b,Object d) { return __juno_invoke(n,new Object[0]); }
        public Object __juno_invoke(int n,Object[] args) {
            jobOwner=Execution.context(); Object r=make("JSONReader"),w=make("JSONWriter"); c(r,"OpenFile","background-in.json"); c(w,"OpenFile","background-out.json"); c(w,"WriteStartArray");
            try { jobReader=channel(r); jobWriter=channel(w); } catch(Exception e) { throw new AssertionError(e); }
            entered.countDown();
            try { new CountDownLatch(1).await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); Execution.checkCancellation(); }
            return UNDEFINED;
        }
    }
    static void cancel(Path tmp) throws Exception {
        Files.writeString(tmp.resolve("background-in.json"),"[1,2]");
        try(var app=new BackgroundJobs(Map.of("Worker",Worker.class),1,2,s -> {},context(tmp))) {
            app.run(() -> {
                entered=new CountDownLatch(1); BackgroundJobs.Job job=app.execute("Worker.block",UNDEFINED,UNDEFINED,""); check(entered.await(2,TimeUnit.SECONDS),"worker acquired handles");
                app.cancel(job); check(job.await(n(2)) && job.state().equals("Отменено"),"JSON cancellation");
                check(!jobReader.isOpen() && !jobWriter.isOpen() && jobOwner.openResourceCount()==0,"cancel closes both JSON channels without writer flush"); return UNDEFINED;
            });
        }
    }
    static void largeChild(Path path,int expected) throws Exception {
        try(var owner=context(path.getParent());var scope=Execution.withContext(owner)) {
            Object r=make("JSONReader"); c(r,"OpenFile",path.getFileName().toString()); int strings=0,numbers=0; long sum=0;
            Object stringType=e("jsonvaluetype","String"),numberType=e("jsonvaluetype","Number");
            while((Boolean)c(r,"Read")) {
                Object type=p(r,"CurrentValueType");
                if(type.equals(stringType)) { String value=(String)p(r,"CurrentValue"); check(value.length()==1024 && value.startsWith("данные"),"large payload"); strings++; }
                if(type.equals(numberType)) { sum+=((BigDecimal)p(r,"CurrentValue")).longValueExact(); numbers++; }
            }
            check(strings==expected && numbers==expected && sum==(long)expected*(expected-1)/2,"large token counts/data"); check(owner.openResourceCount()==0,"EOF closed file");
        }
    }
    static void large(Path tmp) throws Exception {
        Path path=tmp.resolve("large.json"); int count=70_000; String payload="данные"+"a".repeat(1018);
        try(BufferedWriter out=Files.newBufferedWriter(path)) { out.write('['); for(int i=0;i<count;i++) { if(i>0) out.write(','); out.write("{\"i\":"+i+",\"s\":\""+payload+"\"}"); } out.write(']'); }
        check(Files.size(path)>64L*1024*1024,"fixture larger than heap");
        Path error=tmp.resolve("large.err"); Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx32m","-cp",System.getProperty("java.class.path"),"JsonLibraryTests","--large",path.toString(),Integer.toString(count)).redirectError(error.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if(!process.waitFor(30,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("JSON streaming timeout"); }
        check(process.exitValue()==0,Files.readString(error)); Files.delete(path);
    }
    public static void main(String[] args) throws Exception {
        if(args.length>0) { largeChild(Path.of(args[1]),Integer.parseInt(args[2])); return; }
        Path tmp=Files.createTempDirectory("juno-json-tests-");
        try { values(tmp); negative(tmp); fixed(tmp); files(tmp); cancel(tmp); large(tmp); }
        finally { try(var paths=Files.walk(tmp)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
        System.out.println("JSON library PASS");
    }
}
