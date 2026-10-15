package juno.runtime;

import java.util.Arrays;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Mutable, bounded byte storage. Never crosses background-job snapshot boundaries. */
final class BinaryBuffer implements NativeObject, TypedValue {
    final byte[] bytes;
    final Object order;
    BinaryBuffer(int size, Object order) { this(new byte[size], order); }
    BinaryBuffer(byte[] bytes, Object order) { this.bytes = bytes; this.order = order; IoEnums.code(order, IoEnums.BYTE_ORDER); }
    public String bslTypeName() { return "БуферДвоичныхДанных"; }
    Object get(int index) { if (index < 0 || index >= bytes.length) throw error("Индекс буфера вне диапазона"); return number(Integer.toString(bytes[index] & 255)); }
    void set(int index, Object value) {
        int n = integer(value); if (n < 0 || n > 255) throw error("Байт должен быть в диапазоне 0..255");
        if (index < 0 || index >= bytes.length) throw error("Индекс буфера вне диапазона"); bytes[index] = (byte) n;
    }
    void range(int offset, int count) { if (offset < 0 || count < 0 || offset > bytes.length - count) throw error("Диапазон буфера вне границ"); }
    public Object property(String name) {
        return switch (name) {
            case "размер", "size" -> number(Integer.toString(bytes.length));
            case "порядокбайтов", "byteorder" -> order;
            case "толькочтение", "readonly" -> false;
            default -> missingProperty(bslTypeName(), name);
        };
    }
    public Object invoke(String name, Object[] args) {
        args = values(args);
        return switch (name) {
            case "получить", "get" -> { arity(name,args,1,1); yield get(integer(args[0])); }
            case "установить", "set" -> { arity(name,args,2,2); set(integer(args[0]),args[1]); yield UNDEFINED; }
            case "скопировать", "copy" -> { arity(name,args,0,0); yield new BinaryBuffer(bytes.clone(),order); }
            default -> missingMethod(bslTypeName(),name);
        };
    }
}
