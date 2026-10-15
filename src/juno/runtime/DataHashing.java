package juno.runtime;

import java.io.*;
import java.math.BigDecimal;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.Path;
import java.security.*;
import java.util.zip.CRC32;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Incremental digest with bounded I/O buffers; reading HashSum never resets its state. */
final class DataHashing implements NativeObject, TypedValue {
    private static final int CHUNK = 65536;
    private final Object algorithm;
    private final MessageDigest digest;
    private final CRC32 crc;
    DataHashing(Object algorithm) {
        String name = IoEnums.code(algorithm, IoEnums.HASH);
        this.algorithm = algorithm;
        crc = name.equals("CRC32") ? new CRC32() : null;
        try {
            digest = crc == null ? MessageDigest.getInstance(switch (name) {
                case "MD5" -> "MD5"; case "SHA1" -> "SHA-1"; case "SHA256" -> "SHA-256"; case "SHA512" -> "SHA-512";
                default -> throw error("Неподдерживаемая ХешФункция: " + name);
            }) : null;
        } catch (NoSuchAlgorithmException e) { throw failure("Алгоритм хеширования недоступен в JDK", e); }
    }
    public String bslTypeName() { return "ХешированиеДанных"; }
    public String toString() { return bslTypeName(); }
    private void update(byte[] data, int offset, int length) {
        Execution.checkCancellation();
        if (crc != null) crc.update(data, offset, length); else digest.update(data, offset, length);
    }
    public Object property(String name) {
        if (name.equals("хешфункция") || name.equals("hashfunction")) return algorithm;
        if (!name.equals("хешсумма") && !name.equals("hashsum")) return missingProperty(bslTypeName(), name);
        Execution.checkCancellation();
        if (crc != null) return BigDecimal.valueOf(crc.getValue());
        try { return new Binary(((MessageDigest) digest.clone()).digest()); }
        catch (CloneNotSupportedException e) { throw failure("JDK provider не поддерживает чтение хеша без сброса", e); }
    }
    private void text(String text) throws CharacterCodingException {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer input = CharBuffer.wrap(text); ByteBuffer output = ByteBuffer.allocate(CHUNK);
        while (true) {
            CoderResult result = encoder.encode(input, output, true);
            update(output.array(), 0, output.position()); output.clear();
            if (result.isError()) result.throwException();
            if (result.isUnderflow()) break;
        }
        CoderResult flushed = encoder.flush(output);
        if (flushed.isError()) flushed.throwException();
        update(output.array(), 0, output.position());
    }
    private void stream(BinaryStream stream, long count) throws IOException {
        stream.owner.requireOpen(); stream.check();
        if (!stream.readable) throw error("Поток не поддерживает чтение");
        byte[] bytes = new byte[CHUNK];
        while (count > 0) {
            stream.check(); int read = stream.readBytes(bytes, 0, (int) Math.min(bytes.length, count));
            if (read <= 0) break;
            update(bytes, 0, read); count -= read;
        }
        Execution.checkCancellation();
    }
    private static long count(Object value) {
        if (omitted(value)) return Long.MAX_VALUE;
        if (!(value instanceof BigDecimal number)) throw error("Количество байтов требует Число");
        // Both platforms truncate; nonpositive count means the remainder of the stream.
        if (number.compareTo(BigDecimal.ONE) < 0) return Long.MAX_VALUE;
        return number.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) >= 0 ? Long.MAX_VALUE : number.longValue();
    }
    public Object invoke(String name, Object[] raw) {
        Object[] args = values(raw);
        try {
            switch (name) {
                case "добавить", "append": {
                    arity(name, args, 1, 2);
                    if (args[0] instanceof BinaryStream input) {
                        Object countValue = optional(args, 1, UNDEFINED);
                        long count = count(countValue);
                        stream(input, count);
                    } else {
                        if (args[0] instanceof String text) text(text);
                        else if (args[0] instanceof Binary binary) {
                            for (int offset = 0; offset < binary.bytes.length; offset += Math.min(CHUNK, binary.bytes.length - offset))
                                update(binary.bytes, offset, Math.min(CHUNK, binary.bytes.length - offset));
                        } else throw error("Добавить требует Строка, ДвоичныеДанные или Поток");
                    }
                    return UNDEFINED;
                }
                case "добавитьфайл", "appendfile": {
                    arity(name, args, 1, 1); Path path = path(args[0]);
                    try (BinaryStream.File input = new BinaryStream.File(path, "Open", "Read")) { stream(input, Long.MAX_VALUE); }
                    return UNDEFINED;
                }
                default: return missingMethod(bslTypeName(), name);
            }
        } catch (IOException e) { throw failure("Хеширование данных", e); }
    }
}
