package juno.runtime;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Seekable streams. File reads/writes never allocate proportional to file size. */
abstract class BinaryStream implements NativeObject, TypedValue, ApplicationContext.Resource, BinarySink {
    final ApplicationContext owner;
    final boolean readable, writable;
    volatile boolean closed;
    BinaryStream(boolean readable, boolean writable) { owner = Execution.context(); this.readable = readable; this.writable = writable; }
    final void own() { owner.own(this); }
    final void check() { Execution.checkCancellation(); if (closed) throw error("Поток закрыт"); }
    abstract int readBytes(byte[] buffer, int offset, int count) throws IOException;
    abstract void writeBytes(byte[] buffer, int offset, int count) throws IOException;
    abstract long position() throws IOException;
    abstract long size() throws IOException;
    abstract void position(long position) throws IOException;
    abstract void size(long size) throws IOException;
    abstract void flush() throws IOException;
    public final void writeBinary(byte[] bytes,int offset,int count) throws IOException {
        owner.requireOpen(); check(); if(!writable) throw error("Поток не поддерживает запись");
        writeBytes(bytes,offset,count); Execution.checkCancellation();
    }
    public Object property(String name) {
        return switch(name) {
            case "доступночтение", "canread" -> !closed && readable;
            case "доступназапись", "canwrite" -> !closed && writable;
            case "доступноизменениепозиции", "canseek" -> !closed;
            default -> missingProperty(bslTypeName(),name);
        };
    }
    public Object invoke(String name, Object[] args) {
        args = values(args);
        if (name.equals("закрыть") || name.equals("close")) { arity(name,args,0,0); close(); return UNDEFINED; }
        check();
        try {
            return switch(name) {
                case "прочитать", "read" -> {
                    arity(name,args,3,3); if (!readable) throw error("Поток не поддерживает чтение");
                    BinaryBuffer buffer = buffer(args[0]); int offset = integer(args[1]), count = integer(args[2]); buffer.range(offset,count);
                    yield number(Integer.toString(count == 0 ? 0 : Math.max(0,readBytes(buffer.bytes,offset,count))));
                }
                case "записать", "write" -> {
                    arity(name,args,3,3); if (!writable) throw error("Поток не поддерживает запись");
                    BinaryBuffer buffer = buffer(args[0]); int offset = integer(args[1]), count = integer(args[2]); buffer.range(offset,count);
                    writeBinary(buffer.bytes,offset,count); yield UNDEFINED;
                }
                case "текущаяпозиция", "currentposition" -> { arity(name,args,0,0); yield number(Long.toString(position())); }
                case "размер", "size" -> { arity(name,args,0,0); yield number(Long.toString(size())); }
                case "перейти", "seek" -> {
                    arity(name,args,1,2); long offset = longInteger(args[0]);
                    String origin = IoEnums.code(optional(args,1,IoEnums.POSITION.property("начало")),IoEnums.POSITION);
                    long base = switch(origin) { case "Begin" -> 0; case "Current" -> position(); case "End" -> size(); default -> throw new AssertionError(); };
                    long target = Math.addExact(base,offset); if (target < 0) throw error("Позиция потока не может быть отрицательной");
                    position(target); yield number(Long.toString(target));
                }
                case "установитьразмер", "setsize" -> {
                    arity(name,args,1,1); if (!writable) throw error("Поток не поддерживает запись");
                    long length = longInteger(args[0]); if (length < 0) throw error("Размер не может быть отрицательным"); size(length); yield UNDEFINED;
                }
                case "сброситьбуферы", "flush" -> { arity(name,args,0,0); flush(); yield UNDEFINED; }
                case "закрытьиполучитьдвоичныеданные", "closeandgetbinarydata" -> {
                    arity(name,args,0,0); if (!(this instanceof Memory memory)) throw error("Метод доступен только для потока в памяти");
                    Binary result = new Binary(Arrays.copyOf(memory.bytes,memory.length)); close(); yield result;
                }
                default -> missingMethod(bslTypeName(),name);
            };
        } catch (IOException | ArithmeticException e) { throw failure("Операция с потоком",e); }
    }
    static BinaryBuffer buffer(Object value) { if (value instanceof BinaryBuffer b) return b; throw error("Ожидался БуферДвоичныхДанных"); }
    static final class File extends BinaryStream {
        private final FileChannel channel;
        private final long appendStart;
        File(Path path, String mode, String access) {
            super(!access.equals("Write"),!access.equals("Read"));
            if(mode.equals("Append") && !access.equals("Write")) throw error("Дописать допускает только доступ Запись");
            Set<StandardOpenOption> options = new HashSet<>();
            if (readable) options.add(StandardOpenOption.READ); if (writable) options.add(StandardOpenOption.WRITE);
            if (!writable && !mode.equals("Open") && !mode.equals("OpenOrCreate")) throw error("Режим открытия требует доступ для записи");
            switch(mode) {
                case "Create" -> { options.add(StandardOpenOption.CREATE); options.add(StandardOpenOption.TRUNCATE_EXISTING); }
                case "CreateNew" -> options.add(StandardOpenOption.CREATE_NEW);
                case "OpenOrCreate", "Append" -> options.add(StandardOpenOption.CREATE);
                case "Truncate" -> options.add(StandardOpenOption.TRUNCATE_EXISTING);
                case "Open" -> { }
                default -> throw error("Неизвестный режим открытия");
            }
            FileChannel opened=null;
            try {
                IoSupport.regular(path);
                if(mode.equals("OpenOrCreate") && !writable) {
                    try { Files.createFile(path); } catch(FileAlreadyExistsException exists) { }
                }
                opened=FileChannel.open(path,options);
                appendStart=mode.equals("Append") ? opened.size() : 0;
                if(mode.equals("Append")) opened.position(appendStart);
                channel=opened;
            } catch(IOException | RuntimeException e) {
                if(opened!=null) try { opened.close(); } catch(IOException close) { e.addSuppressed(close); }
                if(e instanceof IOException io) throw failure("Открытие потока " + path,io);
                throw (RuntimeException)e;
            }
            own();
        }
        public String bslTypeName() { return "ФайловыйПоток"; }
        int readBytes(byte[] b,int o,int n) throws IOException { return channel.read(ByteBuffer.wrap(b,o,n)); }
        void writeBytes(byte[] b,int o,int n) throws IOException {
            ByteBuffer data=ByteBuffer.wrap(b,o,n); while(data.hasRemaining()) { Execution.checkCancellation(); channel.write(data); }
        }
        long position() throws IOException { return channel.position(); }
        long size() throws IOException { return channel.size(); }
        void position(long n) throws IOException { if(n<appendStart) throw error("Дописать: позиция ниже исходного конца файла"); channel.position(n); }
        void size(long n) throws IOException {
            if(n<appendStart) throw error("Дописать: нельзя обрезать исходные данные");
            long old=channel.position();
            if (n>channel.size()) { channel.position(n-1); channel.write(ByteBuffer.wrap(new byte[1])); }
            else channel.truncate(n);
            channel.position(Math.min(old,n));
        }
        void flush() throws IOException { if (writable) channel.force(false); }
        public void close() {
            if (closed) return; closed=true;
            try { channel.close(); } catch(IOException e) { throw failure("Закрытие потока",e); } finally { owner.release(this); }
        }
        public void cancel() { closed=true; try { channel.close(); } catch(IOException e) { throw error("Ошибка отмены потока: "+e.getMessage()); } finally { owner.release(this); } }
    }
    static final class Memory extends BinaryStream {
        private byte[] bytes;
        private int length, cursor;
        Memory(int capacity) { super(true,true); bytes=new byte[capacity]; own(); }
        public String bslTypeName() { return "ПотокВПамяти"; }
        int readBytes(byte[] b,int o,int n) {
            int count=Math.min(n,Math.max(0,length-cursor)); System.arraycopy(bytes,Math.min(cursor,length),b,o,count); cursor+=count; return count;
        }
        void writeBytes(byte[] b,int o,int n) {
            int end=Math.addExact(cursor,n); grow(end); System.arraycopy(b,o,bytes,cursor,n); cursor=end; length=Math.max(length,end);
        }
        private void grow(int size) { if(size>bytes.length) bytes=Arrays.copyOf(bytes,Math.max(size,(int)Math.min(Integer.MAX_VALUE,Math.max(16L,bytes.length*2L)))); }
        long position() { return cursor; } long size() { return length; }
        void position(long n) { cursor=Math.toIntExact(n); }
        void size(long n) { int next=Math.toIntExact(n); grow(next); if(next<length) Arrays.fill(bytes,next,length,(byte)0); length=next; cursor=Math.min(cursor,length); }
        void flush() { }
        public void close() { closed=true; owner.release(this); }
        public void cancel() { close(); }
    }
}
