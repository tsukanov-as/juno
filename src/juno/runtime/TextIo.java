package juno.runtime;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Streaming text I/O. Cancellation closes the channel, without taking a Reader/Writer lock. */
final class TextIo {
    private TextIo() {}
    static abstract class Handle implements NativeObject, TypedValue, ApplicationContext.Resource {
        final ApplicationContext owner=Execution.context();
        volatile FileChannel channel;
        final void check() { Execution.checkCancellation(); if(channel==null || !channel.isOpen()) throw error("Текстовый файл не открыт"); }
        public Object property(String name) { return missingProperty(bslTypeName(),name); }
        public void cancel() {
            FileChannel current=channel;
            try { if(current!=null) current.close(); }
            catch(IOException e) { throw error("Ошибка отмены текстового I/O: "+e.getMessage()); }
            finally { owner.release(this); }
        }
        final boolean commonClose(String name,Object[] args) {
            if(!name.equals("закрыть") && !name.equals("close")) return false;
            arity(name,args,0,0); close(); return true;
        }
    }
    static final class Input extends Handle {
        private Reader reader;
        private Path source;
        private int pushed=-1;
        private String delimiter="\n";
        Input(Object[] args) { arity("ЧтениеТекста",args,0,3); if(args.length>0) open(args); }
        public String bslTypeName() { return "ЧтениеТекста"; }
        private void open(Object[] args) {
            arity("Открыть",args,1,3); if(channel!=null && channel.isOpen()) throw error("Текстовый файл уже открыт");
            TextEncoding encoding=TextEncoding.value(optional(args,1,UNDEFINED));
            delimiter=string(optional(args,2,"\n")); if(delimiter.isEmpty()) throw error("Разделитель строк не может быть пустым");
            FileChannel opened=null;
            try {
                Path path=path(args[0]); source=path; IoSupport.regular(path);
                opened=FileChannel.open(path,StandardOpenOption.READ);
                channel=opened; owner.own(this);
                PushbackInputStream input=new PushbackInputStream(Channels.newInputStream(opened),4);
                byte[] prefix=input.readNBytes(4); TextEncoding.Bom bom=TextEncoding.detect(prefix,prefix.length,encoding);
                input.unread(prefix,bom.skip(),prefix.length-bom.skip());
                reader=new BufferedReader(new InputStreamReader(input,bom.encoding().decoder()),8192);
                pushed=-1;
            } catch(IOException | RuntimeException | Error e) {
                if(opened!=null) try { opened.close(); } catch(IOException close) { e.addSuppressed(close); }
                owner.release(this);
                if(e instanceof IOException io) throw failure("Открытие текстового файла",io);
                if(e instanceof RuntimeException r) throw r;
                throw (Error)e;
            }
        }
        private int character() throws IOException {
            Execution.checkCancellation();
            int c=pushed>=0 ? pushed : reader.read(); pushed=-1;
            if(c=='\r') { int next=reader.read(); if(next!='\n') pushed=next; return '\n'; }
            return c;
        }
        Object read(Object count) {
            check(); int limit=omitted(count) ? Integer.MAX_VALUE : nonnegative(count);
            if(limit==0) return "";
            StringBuilder result=new StringBuilder();
            try {
                for(int i=0;i<limit;i++) { int c=character(); if(c<0) break; result.append((char)c); }
                return result.isEmpty() ? UNDEFINED : result.toString();
            } catch(IOException e) { throw failure("Чтение текста "+source,e); }
        }
        Object line(String separator) {
            check(); if(separator.isEmpty()) throw error("Разделитель строк не может быть пустым");
            StringBuilder result=new StringBuilder();
            try {
                while(true) {
                    int c=character(); if(c<0) return result.isEmpty() ? UNDEFINED : result.toString();
                    result.append((char)c);
                    if(result.length()>=separator.length() && result.substring(result.length()-separator.length()).equals(separator)) {
                        result.setLength(result.length()-separator.length()); return result.toString();
                    }
                }
            } catch(IOException e) { throw failure("Чтение строки "+source,e); }
        }
        public Object invoke(String name,Object[] args) {
            args=values(args); if(commonClose(name,args)) return UNDEFINED;
            return switch(name) {
                case "открыть", "open" -> { open(args); yield UNDEFINED; }
                case "прочитать", "read" -> { arity(name,args,0,1); yield read(optional(args,0,UNDEFINED)); }
                case "прочитатьстроку", "readline" -> { arity(name,args,0,1); yield line(string(optional(args,0,delimiter))); }
                default -> missingMethod(bslTypeName(),name);
            };
        }
        public void close() {
            FileChannel current=channel; if(current==null) return;
            try { current.close(); }
            catch(IOException e) { throw failure("Закрытие чтения текста",e); }
            finally { channel=null; reader=null; owner.release(this); }
        }
    }
    static final class Output extends Handle {
        private Writer writer;
        private String delimiter="\r\n";
        Output(Object[] args) { arity("ЗаписьТекста",args,0,4); if(args.length>0) open(args); }
        public String bslTypeName() { return "ЗаписьТекста"; }
        private void open(Object[] args) {
            arity("Открыть",args,1,4); if(channel!=null && channel.isOpen()) throw error("Текстовый файл уже открыт");
            TextEncoding encoding=TextEncoding.value(optional(args,1,UNDEFINED)); delimiter=string(optional(args,2,"\r\n"));
            boolean append=truth(optional(args,3,false)); FileChannel opened=null;
            try {
                Path path=path(args[0]); IoSupport.regular(path);
                opened=FileChannel.open(path,StandardOpenOption.WRITE,StandardOpenOption.CREATE,
                    append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
                channel=opened; owner.own(this);
                if(!append || opened.size()==0) {
                    java.nio.ByteBuffer bom=java.nio.ByteBuffer.wrap(encoding.preamble()); while(bom.hasRemaining()) opened.write(bom);
                }
                writer=new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(opened),encoding.encoder()),8192);
            } catch(IOException | RuntimeException | Error e) {
                if(opened!=null) try { opened.close(); } catch(IOException close) { e.addSuppressed(close); }
                owner.release(this);
                if(e instanceof IOException io) throw failure("Открытие записи текста",io);
                if(e instanceof RuntimeException r) throw r;
                throw (Error)e;
            }
        }
        void write(String text,String ending) {
            check(); try { writer.write(text.replace("\n","\r\n")); if(ending!=null) writer.write(ending); }
            catch(IOException e) { throw failure("Запись текста",e); }
        }
        public Object invoke(String name,Object[] args) {
            args=values(args); if(commonClose(name,args)) return UNDEFINED;
            return switch(name) {
                case "открыть", "open" -> { open(args); yield UNDEFINED; }
                case "записать", "write" -> { arity(name,args,1,1); write(string(args[0]),null); yield UNDEFINED; }
                case "записатьстроку", "writeline" -> { arity(name,args,0,2); write(string(optional(args,0,"")),string(optional(args,1,delimiter))); yield UNDEFINED; }
                default -> missingMethod(bslTypeName(),name);
            };
        }
        public void close() {
            FileChannel current=channel; if(current==null) return;
            try { if(current.isOpen() && writer!=null) writer.close(); }
            catch(IOException e) { throw failure("Закрытие записи текста",e); }
            finally {
                try { current.close(); } catch(IOException e) { throw failure("Закрытие файла",e); }
                finally { channel=null; writer=null; owner.release(this); }
            }
        }
    }
}
