package juno.runtime;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Nonblocking text cursors over process spools, and an owned UTF-8 stdin writer. */
final class ProcessText {
    /** Where the output of a child is read from: its spool file, or its bytes once the child is gone. */
    interface Source { int read(ByteBuffer target,long position) throws IOException; long size() throws IOException; void close() throws IOException; }
    private record FileSource(FileChannel file) implements Source {
        public int read(ByteBuffer target,long position) throws IOException {return file.read(target,position);}
        public long size() throws IOException {return file.size();}
        public void close() throws IOException {file.close();}
    }
    private record MemorySource(byte[] bytes) implements Source {
        public int read(ByteBuffer target,long position) {
            if(position>=bytes.length)return -1;
            int count=(int)Math.min(target.remaining(),bytes.length-position);target.put(bytes,(int)position,count);return count;
        }
        public long size() {return bytes.length;}
        public void close() {}
    }
    static final class Output implements NativeObject,TypedValue,AutoCloseable {
        private final ManagedProcess parent;
        private volatile Source source;
        private final CharsetDecoder decoder;
        private final ByteBuffer bytes=ByteBuffer.allocate(65544);
        private final StringBuilder text=new StringBuilder();
        private volatile long position;
        private volatile boolean closed;
        private boolean ended,skipLf;
        Output(ManagedProcess parent,Path path) throws IOException {this.parent=parent;decoder=parent.encoding.decoder();source=new FileSource(FileChannel.open(path,StandardOpenOption.READ));}
        public String bslTypeName(){return "ПотокВыводаТекста";}
        public String toString(){return bslTypeName();}
        private void check(){parent.owner.requireOpen();Execution.checkCancellation();if(closed)throw error("Поток вывода процесса закрыт");}
        public synchronized Object property(String name){check();return switch(name){case "естьданные","hasdata"->available();default->missingProperty(bslTypeName(),name);};}
        private boolean available(){try{return text.length()>0||bytes.position()>0||position<source.size();}catch(IOException e){throw failure("Process output",e);}}
        public synchronized Object invoke(String name,Object[] supplied){Object[] args=values(supplied);if(name.equals("закрыть")||name.equals("close")){arity(name,args,0,0);try{close();return UNDEFINED;}catch(IOException e){throw failure("Close output",e);}}check();
            try{return switch(name){
                case "прочитать","read"->{arity(name,args,0,0);yield read(false);}
                case "прочитатьстроку","readline"->{arity(name,args,0,0);yield read(true);}
                case "прочитатьбайты","readbytes"->{arity(name,args,3,3);BinaryBuffer buffer=BinaryStream.buffer(args[0]);int offset=integer(args[1]),count=integer(args[2]);buffer.range(offset,count);if(text.length()>0||bytes.position()>0||skipLf)throw error("Raw read требует пустого text cursor; не смешивайте неполную строку/sequence с raw");int n=count==0?0:source.read(ByteBuffer.wrap(buffer.bytes,offset,count),position);if(n>0)position+=n;yield number(Integer.toString(Math.max(0,n)));}
                default->missingMethod(bslTypeName(),name);
            };}catch(IOException e){throw failure("Чтение process output",e);}
        }
        private Object read(boolean line) throws IOException {
            check();if(!line&&source.size()-position>16L*1024*1024)throw error("Process Read ограничен 16 МиБ; используйте ReadBytes chunks");
            while(true){
                if(skipLf&&text.length()>0){skipLf=false;if(text.charAt(0)=='\n')text.deleteCharAt(0);}
                if(line){for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c=='\n'||c=='\r'){String result=text.substring(0,i);text.delete(0,i+1);skipLf=c=='\r';return result;}}}
                boolean more=fill();
                if(!more){
                    if(skipLf&&ended)skipLf=false;
                    if(text.length()==0)return UNDEFINED;
                    if(line&&!ended)return UNDEFINED;
                    String result=text.toString();text.setLength(0);return result;
                }
                if(text.length()>16*1024*1024)throw error("Process text read/line превышает 16 МиБ; используйте ReadBytes");
            }
        }
        private boolean fill() throws IOException {
            if(ended)return false;
            int count=source.read(bytes,position);if(count>0)position+=count;
            boolean end=count<=0&&!parent.active();bytes.flip();CharBuffer chars=CharBuffer.allocate(65544);
            CoderResult result=decoder.decode(bytes,chars,end);if(result.isError())result.throwException();bytes.compact();chars.flip();text.append(chars);
            if(end){chars.clear();result=decoder.flush(chars);if(result.isError())result.throwException();chars.flip();text.append(chars);ended=true;}
            return count>0||end&&text.length()>0;
        }
        /** Only used for unredirected output; no public reader shares this cursor. */
        long length() throws IOException {try{return closed?position:source.size();}catch(ClosedChannelException e){return position;}}
        long cursor(){return position;}
        boolean caughtUp() throws IOException {return closed||position>=length();}
        /**
         * The child and everything it started are gone, so the spool cannot grow: keep what is left of it in memory and
         * let go of the file, unless it is larger than the limit. Reads that are in progress finish first.
         */
        synchronized boolean freeze(long limit) throws IOException {
            if(closed)return true;
            long size=source.size();if(size>limit)return false;
            if(source instanceof MemorySource)return true;
            byte[] all=new byte[(int)size];ByteBuffer target=ByteBuffer.wrap(all);
            while(target.hasRemaining()&&source.read(target,target.position())>0);
            if(target.hasRemaining())throw new IOException("Process output spool is shorter than its size");
            source.close();source=new MemorySource(all);return true;
        }
        void forward(OutputStream target) throws IOException {
            ByteBuffer chunk=ByteBuffer.allocate(65536);int n;
            for(int blocks=0;blocks<16&&!closed&&(n=source.read(chunk,position))>0;blocks++){synchronized(target){target.write(chunk.array(),0,n);target.flush();if(target instanceof PrintStream p&&p.checkError())throw new IOException("Process host output error");}position+=n;chunk.clear();}
        }
        public synchronized void close() throws IOException {closed=true;source.close();}
    }
    static final class Input implements NativeObject,TypedValue,AutoCloseable,BinarySink {
        private final ManagedProcess parent;
        private final OutputStream pipe;
        private volatile boolean closed;
        Input(ManagedProcess parent,OutputStream pipe){this.parent=parent;this.pipe=pipe;}
        public String bslTypeName(){return "ПотокВводаТекста";}
        public String toString(){return bslTypeName();}
        public Object property(String name){return missingProperty(bslTypeName(),name);}
        public Object invoke(String name,Object[] supplied){Object[] args=values(supplied);if(name.equals("закрыть")||name.equals("close")){arity(name,args,0,0);try{close();return UNDEFINED;}catch(IOException e){throw failure("Закрытие stdin",e);}}
            if(name.equals("записатьбайты")||name.equals("writebytes")){
                arity(name,args,3,3);BinaryBuffer buffer=BinaryStream.buffer(args[0]);int offset=integer(args[1]),count=integer(args[2]);buffer.range(offset,count);
                try{if(count==0)writeBinary(buffer.bytes,offset,0);for(int sent=0;sent<count;){int chunk=Math.min(65536,count-sent);writeBinary(buffer.bytes,offset+sent,chunk);sent+=chunk;}return UNDEFINED;}catch(IOException e){throw failure("Запись process stdin bytes",e);}
            }
            if(!name.equals("записать")&&!name.equals("write")&&!name.equals("записатьстроку")&&!name.equals("writeline"))return missingMethod(bslTypeName(),name);
            arity(name,args,1,1);String text=string(args[0]);if(name.equals("записатьстроку")||name.equals("writeline"))text+=System.lineSeparator();
            try{BinarySink.write(this,TextEncoding.value("UTF-8").encode(text));return UNDEFINED;}catch(IOException e){throw failure("Запись process stdin",e);}
        }
        public void writeBinary(byte[] bytes,int offset,int count) throws IOException {parent.owner.requireOpen();Execution.checkCancellation();if(closed)throw error("stdin процесса закрыт");pipe.write(bytes,offset,count);pipe.flush();Execution.checkCancellation();}
        public void close() throws IOException {if(!closed){closed=true;pipe.close();}}
    }
}
