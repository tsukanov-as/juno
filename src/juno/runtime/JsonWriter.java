package juno.runtime;

import java.io.*;
import java.math.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** A stateful writer, independent from internal juno.Json configuration parsing. */
final class JsonWriter implements NativeObject, TypedValue, ApplicationContext.Resource {
    private final ApplicationContext owner=Execution.context();
    private volatile FileChannel channel;
    private Writer output;
    private StringWriter text;
    private JsonOptions options=new JsonOptions(new Object[0]);
    private final ArrayDeque<Frame> frames=new ArrayDeque<>();
    private boolean root,closed=true;
    private static final class Frame {
        final boolean object; int count; boolean pending;
        Frame(boolean object) { this.object=object; }
    }
    JsonWriter(Object[] args) { arity("ЗаписьJSON",args,0,0); }
    public String bslTypeName() { return "ЗаписьJSON"; }
    public String toString() { return bslTypeName(); }
    public Object property(String name) {
        owner.requireOpen(); return switch(name) {
            case "проверятьструктуру","validatestructure" -> true;
            case "параметры","settings" -> options;
            default -> missingProperty(bslTypeName(),name);
        };
    }
    public void setProperty(String name,Object value) {
        owner.requireOpen();
        if(!name.equals("проверятьструктуру") && !name.equals("validatestructure")) throw error("Свойство JSON недоступно для записи: "+name);
        if(!JsonOptions.bool(value)) throw error("Отключение проверки структуры JSON не поддерживается");
    }
    public Object invoke(String name,Object[] args) {
        args=values(args);
        try {
            return switch(name) {
                case "установитьстроку","setstring" -> { arity(name,args,0,1); setString(JsonOptions.value(optional(args,0,UNDEFINED))); yield UNDEFINED; }
                case "открытьфайл","openfile" -> {
                    arity(name,args,1,4); open(path(args[0]),JsonOptions.encoding(optional(args,1,UNDEFINED)),JsonOptions.bool(optional(args,2,false)),JsonOptions.value(optional(args,3,UNDEFINED))); yield UNDEFINED;
                }
                case "закрыть","close" -> { arity(name,args,0,0); yield finish(); }
                case "записатьначалообъекта","writestartobject" -> { arity(name,args,0,0); start(true); yield UNDEFINED; }
                case "записатьначаломассива","writestartarray" -> { arity(name,args,0,0); start(false); yield UNDEFINED; }
                case "записатьконецобъекта","writeendobject" -> { arity(name,args,0,0); end(true); yield UNDEFINED; }
                case "записатьконецмассива","writeendarray" -> { arity(name,args,0,0); end(false); yield UNDEFINED; }
                case "записатьимясвойства","writepropertyname" -> { arity(name,args,1,1); if(!(args[0] instanceof String key)) throw error("Имя свойства JSON должно быть строкой"); propertyName(key); yield UNDEFINED; }
                case "записатьзначение","writevalue" -> {
                    if(args.length<1 || args.length>2 || args[0]==Special.MISSING) throw error("WriteValue ожидает 1..2 аргумента");
                    if(JsonOptions.bool(optional(args,1,false))) throw error("Экспоненциальная запись JSON не поддерживается; используйте точную десятичную запись");
                    primitive(args[0]); yield UNDEFINED;
                }
                case "записатьбезобработки","writeraw" -> { arity(name,args,1,1); if(!(args[0] instanceof String raw)) throw error("WriteRaw ожидает строку"); raw(raw); yield UNDEFINED; }
                default -> missingMethod(bslTypeName(),name);
            };
        } catch(RuntimeException | Error e) { abort(e); throw e; }
    }
    private void check() { owner.requireOpen(); Execution.checkCancellation(); if(closed) throw error("Приемник JSON не открыт"); }
    private void initialize(JsonOptions options) {
        owner.requireOpen(); if(!closed) finish();
        this.options=options; frames.clear(); root=false; closed=false; owner.own(this);
    }
    private void setString(JsonOptions settings) { initialize(settings); text=new StringWriter(); output=text; }
    private void open(Path path,TextEncoding encoding,boolean bom,JsonOptions settings) {
        initialize(settings); text=null;
        try {
            regular(path); FileChannel opened=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING); channel=opened;
            // Re-register after acquiring the handle: cancellation may have closed the first registration.
            owner.release(this); owner.own(this);
            if(bom) { ByteBuffer prefix=ByteBuffer.wrap(encoding.preamble()); while(prefix.hasRemaining()) opened.write(prefix); }
            output=new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(opened),encoding.encoder()),8192);
        } catch(IOException e) { throw failure("Открытие JSON для записи",e); }
    }
    private void emit(String text) {
        try { output.write(text); } catch(IOException e) { throw failure("Запись JSON",e); }
    }
    private void indent(int depth) { if(!options.newline.isEmpty()) { emit(options.newline); for(int i=0;i<depth;i++) emit(options.padding); } }
    private void beforeValue() {
        check(); Frame parent=frames.peek();
        if(parent==null) { if(root) throw error("JSON: значение после корня"); root=true; }
        else if(parent.object) { if(!parent.pending) throw error("JSON: значение объекта требует имя свойства"); parent.pending=false; parent.count++; }
        else { if(parent.count++>0) emit(","); indent(frames.size()); }
    }
    void start(boolean object) {
        check(); if(frames.size()>=JsonTokenizer.MAX_DEPTH) throw error("JSON: превышена глубина "+JsonTokenizer.MAX_DEPTH);
        beforeValue(); emit(object ? "{" : "["); frames.push(new Frame(object));
    }
    void end(boolean object) {
        check(); Frame frame=frames.peek();
        if(frame==null || frame.object!=object || frame.pending) throw error("JSON: неверный конец контейнера или отсутствует значение свойства");
        frames.pop(); if(frame.count>0) indent(frames.size()); emit(object ? "}" : "]");
    }
    void propertyName(String name) {
        check(); String encoded=options.quote(name); Frame frame=frames.peek();
        if(frame==null || !frame.object || frame.pending) throw error("JSON: имя свойства вне объекта или без значения предыдущего свойства");
        if(frame.count>0) emit(","); indent(frames.size()); emit(encoded); emit(options.newline.isEmpty() ? ":" : ": "); frame.pending=true;
    }
    void primitive(Object value) {
        String encoded;
        if(value==null || value==UNDEFINED || value==Special.NULL) encoded="null";
        else if(value instanceof String string) encoded=options.quote(string);
        else if(value instanceof BigDecimal number) encoded=JsonOptions.number(number);
        else if(value instanceof BigInteger integer) encoded=JsonOptions.number(new BigDecimal(integer));
        else if(value instanceof Boolean bool) encoded=bool ? "true" : "false";
        else throw error("JSON: неподдерживаемое значение; дату преобразуйте явно ЗаписатьДатуJSON(..., ФорматДатыJSON.ISO)");
        beforeValue(); emit(encoded);
    }
    private void raw(String raw) {
        JsonTokenizer parser=new JsonTokenizer(new StringReader(raw)); boolean value=false;
        while(parser.next()) { if(parser.kind==JsonTokenizer.Kind.Comment) throw error("WriteRaw не допускает комментарии в исходящем JSON"); value=true; }
        if(!value) throw error("WriteRaw ожидает одно значение JSON");
        beforeValue(); emit(raw);
    }
    String finish() {
        owner.requireOpen(); return finishOwned();
    }
    private String finishOwned() {
        if(closed) return "";
        if(!frames.isEmpty() || !root) { BslException failure=error("JSON: документ пуст или не завершен"); abort(failure); throw failure; }
        closed=true; Writer current=output; StringWriter result=text;
        Throwable primary=null;
        try {
            try { current.close(); } catch(IOException e) { throw failure("Закрытие JSON записи",e); }
            return result==null ? "" : result.toString();
        } catch(RuntimeException | Error e) { primary=e; throw e; }
        finally {
            try { releaseChannel(); }
            catch(RuntimeException | Error close) { if(primary!=null) primary.addSuppressed(close); else throw close; }
            finally { output=null; text=null; owner.release(this); }
        }
    }
    private void releaseChannel() {
        FileChannel current=channel; channel=null;
        if(current!=null) try { current.close(); } catch(IOException e) { throw failure("Закрытие JSON файла",e); }
    }
    void abort(Throwable primary) { try { cancel(); } catch(RuntimeException | Error close) { if(close!=primary) primary.addSuppressed(close); } }
    public void cancel() { closed=true; try { releaseChannel(); } finally { output=null; text=null; owner.release(this); } }
    // ApplicationContext marks itself closed before invoking Resource.close().
    public void close() { finishOwned(); }
}
