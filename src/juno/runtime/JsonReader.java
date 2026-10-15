package juno.runtime;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Public string/file reader; a FileChannel can be cancelled without the Reader lock. */
final class JsonReader implements NativeObject, TypedValue, ApplicationContext.Resource {
    final ApplicationContext owner=Execution.context();
    private volatile FileChannel channel;
    JsonTokenizer cursor;
    JsonReader(Object[] args) { arity("ЧтениеJSON",args,0,0); }
    public String bslTypeName() { return "ЧтениеJSON"; }
    public String toString() { return bslTypeName(); }
    private void check() { owner.requireOpen(); Execution.checkCancellation(); if(cursor==null) throw error("Источник JSON не открыт"); }
    public Object property(String name) {
        owner.requireOpen();
        if(name.equals("текущаястрока") || name.equals("currentline")) return cursor==null ? UNDEFINED : number(Long.toString(cursor.line()));
        if(name.equals("текущаяпозиция") || name.equals("currentposition")) return cursor==null ? UNDEFINED : number(Long.toString(cursor.position()));
        check();
        return switch(name) {
            case "типтекущегозначения", "currentvaluetype" -> JsonOptions.VALUE_TYPE.property(cursor.kind.name().toLowerCase(java.util.Locale.ROOT));
            case "текущеезначение", "currentvalue" -> switch(cursor.kind) {
                case None, ArrayStart, ArrayEnd, ObjectStart, ObjectEnd -> throw error("Текущее значение JSON недоступно для "+cursor.kind);
                default -> cursor.value;
            };
            default -> missingProperty(bslTypeName(),name);
        };
    }
    public Object invoke(String name,Object[] args) {
        args=values(args);
        switch(name) {
            case "закрыть", "close": arity(name,args,0,0); close(); return UNDEFINED;
            case "установитьстроку", "setstring": arity(name,args,1,1); if(!(args[0] instanceof String text)) throw error("JSONReader.SetString ожидает строку"); setString(text); return UNDEFINED;
            case "открытьфайл", "openfile": arity(name,args,1,2); open(path(args[0]),JsonOptions.encoding(optional(args,1,UNDEFINED))); return UNDEFINED;
            case "прочитать", "read": arity(name,args,0,0); return next();
            case "пропустить", "skip": arity(name,args,0,0); check(); try { boolean result=cursor.skip(); if(!result) releaseHandle(); return result; } catch(RuntimeException | Error e) { abort(e); throw e; }
            default: return missingMethod(bslTypeName(),name);
        }
    }
    void setString(String text) {
        owner.requireOpen(); close(); cursor=new JsonTokenizer(new StringReader(text)); owner.own(this);
    }
    private void open(Path path,TextEncoding encoding) {
        owner.requireOpen(); close(); FileChannel opened=null;
        try {
            regular(path); opened=FileChannel.open(path,StandardOpenOption.READ); channel=opened; owner.own(this);
            PushbackInputStream input=new PushbackInputStream(Channels.newInputStream(opened),4);
            byte[] prefix=input.readNBytes(4); TextEncoding.Bom bom=TextEncoding.detect(prefix,prefix.length,encoding);
            input.unread(prefix,bom.skip(),prefix.length-bom.skip());
            cursor=new JsonTokenizer(new BufferedReader(new InputStreamReader(input,bom.encoding().decoder()),8192));
        } catch(IOException | RuntimeException | Error e) {
            abort(e); if(e instanceof IOException io) throw failure("Открытие JSON",io);
            if(e instanceof RuntimeException r) throw r; throw (Error)e;
        }
    }
    boolean next() {
        check(); try { boolean result=cursor.next(); if(!result) releaseHandle(); return result; }
        catch(RuntimeException | Error e) { abort(e); throw e; }
    }
    void tail() {
        check(); try { cursor.validateRootTail(); if(cursor.rootComplete()) releaseHandle(); }
        catch(RuntimeException | Error e) { abort(e); throw e; }
    }
    void abort(Throwable error) { try { releaseHandle(); } catch(RuntimeException | Error close) { if(close!=error) error.addSuppressed(close); } }
    private void releaseHandle() {
        FileChannel current=channel; channel=null;
        try { if(current!=null) current.close(); } catch(IOException e) { throw failure("Закрытие JSON",e); } finally { owner.release(this); }
    }
    public void close() { try { releaseHandle(); } finally { cursor=null; } }
    public void cancel() { close(); }
}
