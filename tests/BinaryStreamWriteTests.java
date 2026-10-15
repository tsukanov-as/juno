import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import juno.runtime.*;
import static juno.runtime.RuntimeSupport.*;

/** BinaryData.Write(path/Stream) shares byte sinks without taking ownership of the receiver. */
public final class BinaryStreamWriteTests {
    static void check(boolean ok,Object why) { if(!ok) throw new AssertionError(why); }
    static void fails(Runnable body) { try { body.run(); } catch(RuntimeException expected) { return; } throw new AssertionError("Expected error"); }
    static Object c(Object target,String name,Object...args) { return call(target,name,args); }
    static Object g(String name,Object...args) { return global(name,args); }
    static Object e(String namespace,String member) { return read(property(globalValue(namespace),member)); }
    static Object make(String name,Object...args) { return construct(name,args); }
    static Object n(int value) { return number(Integer.toString(value)); }
    static final class Host extends ByteArrayOutputStream {
        boolean closed;
        public void close() { closed=true; }
    }
    static ApplicationContext context(Path path,OutputStream out,OutputStream err) { return new ApplicationContext(path,Map.of(),InputStream.nullInputStream(),out,err); }
    static void streams(Path tmp) throws Exception {
        Host out=new Host(),err=new Host(); ApplicationContext owner=context(tmp,out,err);
        try(owner;var scope=Execution.withContext(owner)) {
            Object data=g("GetBinaryDataFromHexString","00FF"),empty=g("GetBinaryDataFromString","");
            Object memory=make("MemoryStream"); c(g("GetBinaryDataFromString","ABC"),"Write",memory); c(memory,"Seek",n(1));
            new MethodSite("write").call1(data,memory); check(c(memory,"CurrentPosition").equals(n(3)),"memory current position");
            c(memory,"Seek",n(1)); c(empty,"Write",memory); check(c(memory,"CurrentPosition").equals(n(1)) && c(memory,"Size").equals(n(3)),"zero bytes do not seek/extend");
            check(g("GetHexStringFromBinaryData",c(memory,"CloseAndGetBinaryData")).equals("4100FF"),"memory overwritten range"); fails(() -> c(empty,"Write",memory));
            Object gap=make("MemoryStream"); c(gap,"Seek",n(10)); c(empty,"Write",gap);
            check(c(gap,"Size").equals(n(10)) && c(gap,"CurrentPosition").equals(n(10)),"OS memory zero write extends gap without seeking"); c(gap,"Close");
            Files.writeString(tmp.resolve("data.bin"),"ABC");
            Object file=make("FileStream","data.bin",e("fileopenmode","Open"),e("fileaccess","ReadAndWrite")); c(file,"Seek",n(1));
            c(data,"Записать",file); check(c(file,"CurrentPosition").equals(n(3)) && read(property(file,"CanWrite")).equals(true),"file position and ownership retained"); c(file,"Close");
            check(HexFormat.of().formatHex(Files.readAllBytes(tmp.resolve("data.bin"))).equals("4100ff"),"file bytes");
            Object readonly=make("FileStream","data.bin",e("fileopenmode","Open"),e("fileaccess","Read"));
            fails(() -> c(data,"Write",readonly)); fails(() -> c(empty,"Write",readonly)); c(readonly,"Close"); fails(() -> c(data,"Write",file));
            Object console=globalValue("console"),input=c(console,"OpenStandardInput"); fails(() -> c(data,"Write",input)); c(input,"Close");
            Object error=c(console,"OpenStandardError");
            assign(property(console,"OutputEncoding"),"windows-1251");
            c(g("GetBinaryDataFromString","Ошибка\n",e("textencoding","UTF8")),"Записать",error); c(data,"Write",error); c(empty,"Write",error); c(error,"Close");
            fails(() -> c(data,"Write",error)); Object reopened=c(console,"OpenStandardError"); c(g("GetBinaryDataFromString","!"),"Write",reopened); c(reopened,"Close");
            check(!err.closed && owner.openResourceCount()==0,"host wrapper reopen, no host close/no resource transfer");
            c(data,"Write","path.bin"); check(Arrays.equals(Files.readAllBytes(tmp.resolve("path.bin")),new byte[]{0,(byte)255}),"legacy path write retained");
            c(empty,"Write","empty.bin"); check(Files.size(tmp.resolve("empty.bin"))==0,"empty path write");
        }
        byte[] text="Ошибка\n".getBytes(StandardCharsets.UTF_8),expected=Arrays.copyOf(text,text.length+3); expected[text.length]=0; expected[text.length+1]=(byte)255; expected[text.length+2]='!';
        check(Arrays.equals(err.toByteArray(),expected) && out.size()==0,"exact UTF8 stderr/raw NUL255 without BOM/stdout");
        check(!out.closed && !err.closed,"scope never closes host outputs");
        Host log=new Host(); PrintStream broken=new PrintStream(new OutputStream() { public void write(int b) throws IOException { throw new IOException("broken pipe"); } });
        try(var failed=context(tmp,broken,log);var scope=Execution.withContext(failed)) {
            Object target=c(globalValue("console"),"OpenStandardOutput"); fails(() -> c(g("GetBinaryDataFromString","x"),"Write",target)); c(target,"Close");
        }
        ApplicationContext closed=context(tmp,out,err); Object stale;
        try(var scope=Execution.withContext(closed)) { stale=make("MemoryStream"); }
        closed.close();
        try(var current=context(tmp,out,err);var scope=Execution.withContext(current)) { fails(() -> c(g("GetBinaryDataFromString","x"),"Write",stale)); }
    }
    static void cancellation(Path tmp) {
        int[] calls={0},bytes={0}; OutputStream out=new OutputStream() {
            public void write(int b) { throw new AssertionError("bulk destination expected"); }
            public void write(byte[] b,int offset,int count) { calls[0]++; bytes[0]+=count; Thread.currentThread().interrupt(); }
        };
        try(var owner=context(tmp,out,out);var scope=Execution.withContext(owner)) {
            Object target=c(globalValue("console"),"OpenStandardOutput"),data=g("GetBinaryDataFromString","x".repeat(200_000));
            try { c(data,"Write",target); throw new AssertionError("Expected cancellation between chunks"); }
            catch(Execution.Cancelled expected) { }
            finally { Thread.interrupted(); }
            check(calls[0]==1 && bytes[0]==65536,"bounded chunks and cancellation checks"); c(target,"Close"); check(owner.openResourceCount()==0,"cancel path retains explicit resource lifetime");
        }
    }
    public static void main(String[] args) throws Exception {
        Path tmp=Files.createTempDirectory("juno-binary-write-");
        try { streams(tmp); cancellation(tmp); }
        finally { try(var paths=Files.walk(tmp)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
        System.out.println("BinaryData stream write PASS");
    }
}
