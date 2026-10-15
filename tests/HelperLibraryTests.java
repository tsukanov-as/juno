import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import juno.Main;
import juno.runtime.*;
import static juno.runtime.RuntimeSupport.*;

/** UUID boundaries, streaming hash ownership and bounded allocation, PRNG/Math/date contracts. */
public final class HelperLibraryTests {
    static BigDecimal n(long value) { return BigDecimal.valueOf(value); }
    static Object make(String name, Object... args) { return construct(name, args); }
    static Object p(Object object, String name) { return read(property(object, name)); }
    static Object c(Object object, String name, Object... args) { return call(object, name, args); }
    static Object g(String name, Object... args) { return global(name, args); }
    static void put(Object object, String name, Object value) { assign(property(object, name), value); }
    static void check(boolean ok, Object message) { if (!ok) throw new AssertionError(message); }
    static BslException fails(Runnable body) {
        try { body.run(); } catch (BslException expected) { return expected; }
        throw new AssertionError("Expected a BSL error");
    }
    static String hex(Object bytes) { return (String) g("GetHexStringFromBinaryData", bytes); }
    static Object algorithm(String name) { return p(globalValue("hashfunction"), name); }
    static Object hash(String name) { return make("DataHashing", algorithm(name)); }
    static String expected(String algorithm, byte[] bytes) throws Exception {
        return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    }
    static void uuid() {
        UUID zero = (UUID) make("UUID", "00000000-0000-0000-0000-000000000000");
        UUID first = (UUID) make("УникальныйИдентификатор"), second = (UUID) make("UUID");
        check(first.version() == 4 && first.variant() == 2 && !first.equals(second), "v4 UUID and independent entropy");
        String text = "aabbccdd-1122-3344-5566-778899aabbcc";
        Object id = make("UUID", text.toUpperCase(Locale.ROOT));
        check(string(id).equals(text) && valueType(id).equals(type("УникальныйИдентификатор")), "canonical UUID presentation and type");
        check(g("ValueIsFilled", zero).equals(false) && g("ЗначениеЗаполнено", id).equals(true), "zero UUID unfilled");
        check(g("XMLString", id).equals(text) && g("XMLValue", type("UUID"), text).equals(id), "UUID XML lexical round trip");
        check(ValueCopy.snapshot(id) == id, "immutable UUID job transport");
        Object description = make("TypeDescription", "UUID");
        check(c(description, "AdjustValue").equals(zero) && c(description, "AdjustValue", text).equals(zero)
            && c(description, "AdjustValue", id) == id, "UUID typed default without implicit string parsing");
        Object table = make("ValueTable"); c(p(table, "Columns"), "Add", "ID", description);
        Object row = c(table, "Add"); check(p(row, "ID").equals(zero), "UUID typed row default");
        for (String invalid : List.of("1-2-3-4-5", "{" + text + "}", text + " ", text.replace('-', '_'), text.substring(1)))
            fails(() -> make("UUID", invalid));
        fails(() -> make("UUID", n(1))); fails(() -> make("UUID", text, text));
        fails(() -> g("XMLValue", type("UUID"), " " + text));
        Object writer = make("JSONWriter"); c(writer, "SetString"); fails(() -> g("WriteJSON", writer, id)); c(writer, "Close");
    }
    static void random() {
        Object first = make("RandomNumberGenerator", n(-1)), second = make("ГенераторСлучайныхЧисел", n(-1));
        Set<Object> samples = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            Object value = c(first, "RandomNumber"); samples.add(value);
            check(value.equals(c(second, "СлучайноеЧисло")), "equal seeds produce repeatable sequence");
            BigDecimal number = (BigDecimal) value;
            check(number.scale() == 0 && number.signum() >= 0 && number.compareTo(n(4294967295L)) <= 0, "default uint32 range");
        }
        check(samples.size() > 90, "generator advances state");
        check(c(first, "RandomNumber", n(4294967295L), n(4294967295L)).equals(n(4294967295L)), "inclusive maximum");
        check(c(first, "RandomNumber", n(0), n(0)).equals(n(0)), "inclusive minimum");
        check(c(first, "RandomNumber", new BigDecimal("7.9"), new BigDecimal("7.9")).equals(n(7)), "fractional bounds truncate");
        fails(() -> c(first, "RandomNumber", n(-1), n(1))); fails(() -> c(first, "RandomNumber", n(10), n(1)));
        fails(() -> c(first, "RandomNumber", n(0), n(4294967296L))); fails(() -> make("RandomNumberGenerator", "42"));
        fails(() -> c(first, "RandomNumber", n(1), n(2), n(3)));
    }
    static void mathAndDates() {
        check(decimal(g("Pow", n(2), n(-3))).compareTo(new BigDecimal("0.125")) == 0, "negative exponent");
        check(g("Sqrt", n(2)).equals(BigDecimal.valueOf(StrictMath.sqrt(2))), "documented StrictMath result boundary");
        fails(() -> g("Sqrt", n(-1))); fails(() -> g("Log", n(0))); fails(() -> g("Log10", n(-1)));
        fails(() -> g("Pow", n(-1), new BigDecimal("0.5"))); fails(() -> g("ASin", n(2)));
        fails(() -> g("Exp", n(1000))); fails(() -> g("Sin", new BigDecimal("1E10000")));
        check(g("Exp", n(-1000)).equals(BigDecimal.valueOf(0.0)), "double underflow rounds to zero");
        LocalDateTime max = LocalDateTime.of(9999, 12, 31, 13, 14, 15), min = LocalDateTime.of(1, 1, 1, 0, 0);
        check(g("EndOfWeek", max).equals(max.withHour(23).withMinute(59).withSecond(59)), "last week clamps to BSL maximum");
        check(g("BegOfWeek", min).equals(min), "first week stays within range");
        check(g("WeekOfYear", LocalDateTime.of(2012, 12, 31, 0, 0)).equals(n(54)), "week 54, not ISO week based year");
        check(g("WeekDay", LocalDateTime.of(2023, 1, 1, 0, 0)).equals(n(7)), "Sunday is day seven");
        LocalDateTime d = LocalDateTime.of(2024, 2, 29, 12, 34, 56, 123);
        check(g("EndOfQuarter", d).equals(LocalDateTime.of(2024, 3, 31, 23, 59, 59)), "quarter boundary drops fractions");
        check(g("BegOfHour", d).equals(LocalDateTime.of(2024, 2, 29, 12, 0)), "hour boundary");
        check(g("EndOfMinute", d).equals(d.withSecond(59).withNano(0)), "minute boundary");
    }
    static ApplicationContext context(Path path) {
        return new ApplicationContext(path, Map.of(), InputStream.nullInputStream(), OutputStream.nullOutputStream(), OutputStream.nullOutputStream());
    }
    static void hashing(Path root) throws Exception {
        try (var io = context(root); var scope = Execution.withContext(io)) {
            String text = "x".repeat(65535) + "😀Ёж";
            for (String algorithm : List.of("MD5", "SHA1", "SHA256", "SHA512")) {
                Object hash = hash(algorithm);
                check(p(hash, "ХешФункция") == algorithm(algorithm), "readonly hash algorithm");
                fails(() -> put(hash, "HashFunction", algorithm("MD5")));
                c(hash, "Append", text);
                String javaName = algorithm.startsWith("SHA") ? "SHA-" + algorithm.substring(3) : algorithm;
                check(hex(p(hash, "HashSum")).equals(expected(javaName, text.getBytes(StandardCharsets.UTF_8))), "UTF8 encoding across fixed buffer boundary: " + algorithm);
                String before = hex(p(hash, "HashSum"));
                ((Binary) p(hash, "HashSum")).bytes[0] ^= 1;
                check(hex(p(hash, "HashSum")).equals(before), "digest read gives independent immutable-state snapshot");
                c(hash, "Append", "!");
                check(hex(p(hash, "ХешСумма")).equals(expected(javaName, (text + "!").getBytes(StandardCharsets.UTF_8))), "read does not reset digest");
                fails(() -> put(hash, "HashSum", n(0))); fails(() -> c(hash, "Clear"));
            }
            Object ignoredCount = hash("SHA256"); c(ignoredCount, "Append", "abc", n(1));
            check(hex(p(ignoredCount, "HashSum")).equals(expected("SHA-256", "abc".getBytes(StandardCharsets.UTF_8))), "text append ignores optional count as both platforms do");
            Object crc = hash("CRC32"); c(crc, "Append", "123456789");
            check(p(crc, "HashSum").equals(n(3421780262L)), "unsigned CRC32");
            Object input = make("MemoryStream"); c(g("GetBinaryDataFromString", "abcdef"), "Write", input); c(input, "Seek", n(2));
            Object hash = hash("SHA256"); c(hash, "Append", input, new BigDecimal("2.9"));
            check(c(input, "CurrentPosition").equals(n(4)) && p(input, "CanRead").equals(true), "Append consumes requested bytes and leaves caller stream open");
            check(hex(p(hash, "HashSum")).equals(expected("SHA-256", "cd".getBytes(StandardCharsets.UTF_8))), "Append starts at current position");
            c(hash, "Append", input, n(999));
            check(hex(p(hash, "HashSum")).equals(expected("SHA-256", "cdef".getBytes(StandardCharsets.UTF_8))), "Append clips count at EOF");
            for (long count : new long[]{0, -1}) {
                c(input, "Seek", n(1)); Object rest = hash("SHA256"); c(rest, "Append", input, n(count));
                check(hex(p(rest, "HashSum")).equals(expected("SHA-256", "bcdef".getBytes(StandardCharsets.UTF_8))), "nonpositive count reads to EOF without native 1C hang");
            }
            c(input, "Close"); fails(() -> c(hash, "Append", input));
            Path file = Files.writeString(root.resolve("данные.bin"), "Ёж😀", StandardCharsets.UTF_8);
            int resources = io.openResourceCount(); Object fileHash = hash("SHA256"); c(fileHash, "AppendFile", file.getFileName().toString());
            check(io.openResourceCount() == resources, "AppendFile closes its owned stream");
            check(hex(p(fileHash, "HashSum")).equals(expected("SHA-256", Files.readAllBytes(file))), "file hashes bytes without BOM insertion");
            fails(() -> c(fileHash, "AppendFile", "missing.bin")); check(io.openResourceCount() == resources, "failed file open leaks no owned handle");
            fails(() -> c(fileHash, "AppendFile", root.toString()));
            Object binaryHash = hash("SHA256"); c(binaryHash, "Append", g("GetBinaryDataFromString", "abc"), n(1));
            check(hex(p(binaryHash, "HashSum")).equals(expected("SHA-256", "abc".getBytes(StandardCharsets.UTF_8))), "BinaryData hashing");
            BslException invalidUnicode = fails(() -> c(binaryHash, "Append", "\ud800"));
            check(invalidUnicode.getCause() instanceof java.nio.charset.CharacterCodingException, "invalid UTF16 has encoding cause");
            Thread.currentThread().interrupt();
            try { c(fileHash, "AppendFile", file.toString()); throw new AssertionError("Cancellation expected"); }
            catch (Execution.Cancelled expected) { }
            finally { Thread.interrupted(); }
            check(io.openResourceCount() == resources, "cancelled hash owns no leaked input");
            check(ValueCopy.snapshot(globalValue("hashfunction")) == globalValue("hashfunction"), "immutable hash enum transport");
            fails(() -> make("DataHashing", "SHA256")); fails(() -> p(globalValue("hashfunction"), "PBKDF2SHA256"));
        }
    }
    static void boundedFile(Path root) throws Exception {
        Path large = root.resolve("large.bin"); byte[] chunk = new byte[1024 * 1024]; Arrays.fill(chunk, (byte) 0x5a);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (OutputStream output = Files.newOutputStream(large)) {
            for (int i = 0; i < 64; i++) { output.write(chunk); digest.update(chunk); }
        }
        String wanted = HexFormat.of().withUpperCase().formatHex(digest.digest());
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(java, "-Xmx24m", "-cp", System.getProperty("java.class.path"),
            "HelperLibraryTests", "--bounded", large.toString(), wanted).inheritIO().start();
        if (!child.waitFor(30, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("bounded hash child timed out"); }
        check(child.exitValue() == 0, "64MiB file hashing fits in a 24MiB heap");
    }
    static void cli(Path root) throws Exception {
        Path entry = Files.writeString(root.resolve("Main.bsl"), "Х=Новый ХешированиеДанных(ХешФункция.SHA256);\nХ.ДобавитьФайл(\"missing.bin\");");
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        int code = Main.execute(new String[]{"run", entry.toString()}, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        String error = err.toString(StandardCharsets.UTF_8);
        check(code == 3 && out.size() == 0 && error.contains("Main.bsl:2") && error.contains("missing.bin"), "hash I/O diagnostics keep BSL source path/line");
        out.reset(); err.reset();
        code = Main.execute(new String[]{"run", "examples/помощники/Программа.bsl"}, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        check(code == 0 && err.size() == 0 && out.toString(StandardCharsets.UTF_8).contains("SHA256: A3FA52E4"), "working helper example");
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            Path file = Path.of(args[1]);
            try (var io = context(file.getParent()); var scope = Execution.withContext(io)) {
                Object hash = hash("SHA256"); c(hash, "AppendFile", file.toString());
                check(hex(p(hash, "HashSum")).equals(args[2]), "bounded streaming digest");
            }
            return;
        }
        Path root = Files.createTempDirectory("juno-helpers-java-");
        try (var io = context(root); var scope = Execution.withContext(io)) { uuid(); random(); mathAndDates(); hashing(root); boundedFile(root); cli(root); }
        finally { try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); } }
        System.out.println("Helper library PASS");
    }
}
