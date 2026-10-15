package juno.runtime;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Borrowed host streams: no global readers, BOMs, terminal control, or closing System streams. */
public final class ConsoleIo implements NativeObject, TypedValue {
    private static final class CliOutput extends PrintStream {
        CliOutput(){super(System.out,true,StandardCharsets.UTF_8);}
    }
    public static PrintStream standardOutput(){return new CliOutput();}
    public static PrintStream standardError(){return new PrintStream(System.err,true,StandardCharsets.UTF_8);}
    public static boolean closedStandardOutput(OutputStream stream){return (stream==System.out||stream instanceof CliOutput)&&stream instanceof PrintStream print&&print.checkError();}
    final ApplicationContext owner;
    final Input input;
    private volatile TextEncoding inputEncoding, outputEncoding;
    ConsoleIo(ApplicationContext owner, Input input, ConsoleIo previous) {
        this.owner=owner; this.input=input;
        inputEncoding=previous==null ? TextEncoding.value("UTF8NoBOM") : previous.inputEncoding;
        outputEncoding=previous==null ? TextEncoding.value("UTF8NoBOM") : previous.outputEncoding;
    }
    public String bslTypeName() { return "Консоль"; }
    public String toString() { return bslTypeName(); }
    private void check() { owner.requireOpen(); Execution.checkCancellation(); }
    private void allowInput() {
        Execution.checkCancellation();
        if(Execution.job()!=null || !owner.standardInputAllowed()) throw error("Чтение общего stdin в фоновом задании или lint не поддерживается; передайте данные параметром или через файл");
    }
    public Object property(String name) {
        check(); return switch(name) {
            case "кодировкавходногопотока", "inputencoding" -> encodingValue(inputEncoding);
            case "кодировкавыходногопотока", "outputencoding" -> encodingValue(outputEncoding);
            default -> missingProperty(bslTypeName(),name);
        };
    }
    public void setProperty(String name,Object value) {
        check(); TextEncoding encoding=consoleEncoding(value);
        switch(name) {
            case "кодировкавходногопотока", "inputencoding" -> inputEncoding=encoding;
            case "кодировкавыходногопотока", "outputencoding" -> outputEncoding=encoding;
            default -> throw error("Консоль: свойство недоступно для записи: "+name);
        }
    }
    private static TextEncoding consoleEncoding(Object value) {
        if(omitted(value)) throw error("Консоль: кодировка должна быть задана явно");
        TextEncoding encoding=TextEncoding.value(value);
        encodingValue(encoding); // Limit to codecs represented by the supported TextEncoding enum.
        return encoding;
    }
    private static Object encodingValue(TextEncoding encoding) {
        String code=switch(encoding.charset().name()) {
            case "UTF-8" -> "utf8безbom";
            case "UTF-16LE" -> "utf16";
            case "windows-1251" -> "ansi";
            case "IBM866" -> "oem";
            default -> throw error("Кодировка консоли не поддерживается: "+encoding.charset().name()+"; доступны UTF-8, UTF-16LE, windows-1251, IBM866");
        };
        return IoEnums.ENCODING.property(code);
    }
    public Object invoke(String name,Object[] args) {
        args=values(args); check();
        switch(name) {
            case "прочитатьстроку", "readline": {
                arity(name,args,0,0); allowInput();
                try { return input.line(inputEncoding); } catch(IOException e) { throw failure("Чтение stdin",e); }
            }
            case "вывести", "write", "вывестистроку", "writeline": {
                // Undefined/Null are valid empty text, unlike an absent argument.
                if(args.length!=1 || args[0]==Special.MISSING) throw error(name+": ожидается 1 аргумент");
                writeText(string(args[0]),name.equals("вывестистроку") || name.equals("writeline")); return UNDEFINED;
            }
            case "открытьстандартныйпотокввода", "openstandardinput": arity(name,args,0,0); allowInput(); return new StandardStream(true,false);
            case "открытьстандартныйпотоквывода", "openstandardoutput": arity(name,args,0,0); return new StandardStream(false,false);
            case "открытьстандартныйпотокошибок", "openstandarderror": arity(name,args,0,0); return new StandardStream(false,true);
            default: return missingMethod(bslTypeName(),name);
        }
    }
    /** Message sink for CLI run and built programs; other host message sinks remain host-defined. */
    public void writeLine(String text) { writeText(text,true); }
    private void writeText(String text,boolean line) {
        check(); byte[] bytes=outputEncoding.encodeReplacing(line ? text+System.lineSeparator() : text);
        try { write(owner.output(),bytes,0,bytes.length); } catch(IOException e) { throw failure("Вывод stdout",e); }
    }
    private static void write(OutputStream stream,byte[] bytes,int offset,int count) throws IOException {
        synchronized(stream) {
            stream.write(bytes,offset,count); stream.flush();
            if(stream instanceof PrintStream print && print.checkError()) {
                if(closedStandardOutput(stream))throw new Exit(0);
                throw new IOException("Ошибка стандартного потока вывода (возможно, канал закрыт)");
            }
        }
        Execution.checkCancellation();
    }
    /** A shared byte cursor across snapshots and raw/text views. No reader can prefetch raw bytes. */
    static final class Input {
        final InputStream stream;
        private boolean skipLineFeed;
        Input(InputStream stream) { this.stream=stream; }
        synchronized int raw(byte[] bytes,int offset,int count) throws IOException {
            Execution.checkCancellation(); if(count>0) skipLineFeed=false;
            return count==0 ? 0 : Math.max(0,stream.read(bytes,offset,count));
        }
        synchronized Object line(TextEncoding encoding) throws IOException {
            Execution.checkCancellation(); StringBuilder line=new StringBuilder();
            CharsetDecoder decoder=encoding.decoder();
            byte[] unit=new byte[8]; CharBuffer chars=CharBuffer.allocate(2);
            while(true) {
                String c=unit(decoder,unit,chars);
                if(c==null) { skipLineFeed=false; return line.isEmpty() ? UNDEFINED : line.toString(); }
                if(skipLineFeed) { skipLineFeed=false; if(c.equals("\n")) continue; }
                if(c.equals("\r")) { skipLineFeed=true; return line.toString(); }
                if(c.equals("\n")) return line.toString();
                line.append(c);
            }
        }
        private String unit(CharsetDecoder decoder,byte[] bytes,CharBuffer chars) throws IOException {
            int length=0;
            while(true) {
                Execution.checkCancellation(); int next=stream.read();
                if(next==-1 && length==0) return null;
                if(next!=-1) bytes[length++]=(byte)next;
                ByteBuffer data=ByteBuffer.wrap(bytes,0,length); chars.clear(); decoder.reset();
                CoderResult result=decoder.decode(data,chars,next==-1);
                if(result.isError()) result.throwException();
                if(chars.position()>0) {
                    if(data.hasRemaining()) throw new IOException("Неподдерживаемое состояние декодера stdin");
                    chars.flip(); return chars.toString();
                }
                if(next==-1 || length==bytes.length) throw new IOException("Незавершенная последовательность stdin");
            }
        }
    }
    private final class StandardStream implements NativeObject, TypedValue, ApplicationContext.Resource, BinarySink {
        private final boolean read, stderr;
        private volatile boolean closed;
        StandardStream(boolean read,boolean stderr) { this.read=read; this.stderr=stderr; owner.own(this); }
        public String bslTypeName() { return "Поток"; }
        public String toString() { return bslTypeName(); }
        public void writeBinary(byte[] bytes,int offset,int count) throws IOException {
            check(); if(closed) throw error("Поток закрыт"); if(read) throw error("Поток не поддерживает запись");
            write(stderr ? owner.error() : owner.output(),bytes,offset,count);
        }
        public Object property(String name) {
            return switch(name) {
                case "доступночтение", "canread" -> !closed && read;
                case "доступназапись", "canwrite" -> !closed && !read;
                case "доступноизменениепозиции", "canseek" -> false;
                default -> missingProperty(bslTypeName(),name);
            };
        }
        public Object invoke(String name,Object[] args) {
            args=values(args);
            if(name.equals("закрыть") || name.equals("close")) { arity(name,args,0,0); close(); return UNDEFINED; }
            check(); if(closed) throw error("Поток закрыт");
            try {
                return switch(name) {
                    case "прочитать", "read" -> {
                        arity(name,args,3,3); if(!read) throw error("Поток не поддерживает чтение");
                        allowInput();
                        BinaryBuffer buffer=BinaryStream.buffer(args[0]); int offset=integer(args[1]),count=integer(args[2]); buffer.range(offset,count);
                        yield number(Integer.toString(input.raw(buffer.bytes,offset,count)));
                    }
                    case "записать", "write" -> {
                        arity(name,args,3,3); if(read) throw error("Поток не поддерживает запись");
                        BinaryBuffer buffer=BinaryStream.buffer(args[0]); int offset=integer(args[1]),count=integer(args[2]); buffer.range(offset,count);
                        writeBinary(buffer.bytes,offset,count); yield UNDEFINED;
                    }
                    case "сброситьбуферы", "flush" -> {
                        arity(name,args,0,0); if(!read) write(stderr ? owner.error() : owner.output(),new byte[0],0,0); yield UNDEFINED;
                    }
                    case "перейти", "seek", "текущаяпозиция", "currentposition", "размер", "size", "установитьразмер", "setsize" -> throw error("Стандартный поток не поддерживает позицию и размер");
                    default -> missingMethod(bslTypeName(),name);
                };
            } catch(IOException e) { throw failure("Стандартный поток",e); }
        }
        // Host owns the underlying handle; wrapper close/cancel never flushes or closes it.
        public void close() { closed=true; owner.release(this); }
        public void cancel() { close(); }
    }
}
