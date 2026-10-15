import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import juno.Juno;
import juno.Main;
import juno.runtime.*;
import static juno.runtime.RuntimeSupport.*;

/** Regex object boundaries, bounded work/cache and generated-code/CLI integration. */
public final class RegexLibraryTests {
    static final String FIND = "StrFindByRegularExpression", ALL = "StrFindAllByRegularExpression",
        REPLACE = "StrReplaceByRegularExpression", LIKE = "StrLikeByRegularExpression";
    static void check(boolean ok, Object message) { if (!ok) throw new AssertionError(message); }
    static Object g(String name, Object... arguments) { return global(name, arguments); }
    static Object p(Object object, String name) { return read(property(object, name)); }
    static Object c(Object object, String name, Object... arguments) { return call(object, name, arguments); }
    static BigDecimal n(int value) { return BigDecimal.valueOf(value); }
    static RuntimeException fails(Runnable action) {
        try { action.run(); }
        catch (RuntimeException expected) { return expected; }
        throw new AssertionError("Expected BSL error");
    }
    static void result(Object value, int start, int length, String text) {
        check(p(value, "StartIndex").equals(n(start)) && p(value, "Длина").equals(n(length))
            && p(value, "Value").equals(text), "result: " + start + "/" + length + "/" + text);
    }
    static void basics() {
        Object found = g(FIND, "x😀ЁЖ42", "(ЁЖ)([0-9]+)");
        result(found, 4, 4, "ЁЖ42");
        check(valueType(found).equals(type("ResultOfSearchByRegularExpression")), "EN result type");
        check(valueType(found).equals(type("РезультатПоискаПоРегулярномуВыражению")), "RU result type");
        Object groups = c(found, "GetGroups");
        check(c(groups, "Count").equals(n(2)), "capturing groups exclude group zero");
        Object first = c(groups, "Get", n(0));
        result(first, 4, 2, "ЁЖ");
        check(valueType(first).equals(type("ResultOfSearchByRegularExpressionGroup")), "group type");
        fails(() -> assign(property(found, "Value"), "other"));
        fails(() -> assign(property(first, "НачальнаяПозиция"), n(99)));
        fails(() -> c(first, "GetGroups"));
        fails(() -> c(found, "GetGroups", n(1)));
        c(groups, "Clear");
        check(c(c(found, "ПолучитьГруппы"), "Количество").equals(n(2)), "fresh mutable group arrays");
        check(ValueCopy.snapshot(found) == found && ValueCopy.snapshot(first) == first, "immutable job transport");
        result(g(FIND, "abc", "Z"), 0, 0, "");
        result(g(FIND, "a😀b", ".", SearchDirection.FROM_BEGINNING, n(2)), 2, 2, "😀");
        check(g(LIKE, "ёж", "ЁЖ", true).equals(true), "Unicode case folding");
        check(g(LIKE, "ёж", "ЁЖ").equals(false), "case sensitive default");
        check(g(LIKE, "x42y", "[0-9]+").equals(false), "whole string match");
        check(g(LIKE, "42", "[0-9]+").equals(true), "whole match");
        check(g(LIKE, "Ж９", "\\w\\d").equals(true), "Unicode classes");
        check(g(LIKE, "ß", "ss", true).equals(false), "documented JDK simple folding boundary");
        check(c(g(ALL, "one\ntwo\nthree", "^two$", false, true), "Count").equals(n(1)), "multiline anchors");
        check(c(g(ALL, "one\ntwo\nthree", "^two$"), "Count").equals(n(0)), "multiline default off");
        check(g(LIKE, "a\nb", "a.b").equals(false), "dot does not silently enable DOTALL");
        check(g(LIKE, "a\nb", "(?s)a.b").equals(true), "explicit DOTALL");
        for (String alias : List.of("СтрПодобнаПоРегулярномуВыражению", "STRLIKEBYREGULAREXPRESSION", "стрподобнапорегулярномувыражению"))
            check(g(alias, "ab", "a.").equals(true), alias);
        check(g(REPLACE, "2024-02-29 2025-01-01", "(\\d{4})-(\\d{2})-(\\d{2})", "$3.$2.$1")
            .equals("29.02.2024 01.01.2025"), "all replacements and group references");
    }
    static void platformEdges() {
        result(g(FIND, "abc", ""), 0, 0, "");
        result(g(FIND, "", ""), 0, 0, "");
        check(c(g(ALL, "abc", ""), "Count").equals(n(0)), "platform empty-pattern search");
        check(c(g(ALL, "", "["), "Count").equals(n(0)), "empty source bypasses pattern compilation for search");
        check(c(g(ALL, "", ""), "Count").equals(n(0)) && g(LIKE, "", "").equals(true), "empty search versus whole-match");
        Object zeros = g(ALL, "😀", "(?=)");
        check(c(zeros, "Count").equals(n(2)), "empty-match progress by code point");
        result(c(zeros, "Get", n(0)), 1, 0, "");
        result(c(zeros, "Get", n(1)), 3, 0, "");
        Object end = g(ALL, "a", "a*");
        check(c(end, "Count").equals(n(2)), "include empty end match");
        result(c(end, "Get", n(1)), 2, 0, "");
        result(g(FIND, "ababa", "aba", SearchDirection.FROM_END), 3, 3, "aba");
        result(g(FIND, "ababa", "aba", SearchDirection.FROM_END, UNDEFINED, n(2)), 0, 0, "");
        result(g(FIND, "ababa", "aba", SearchDirection.FROM_BEGINNING, n(2)), 3, 3, "aba");
        result(g(FIND, "abc", "bc", SearchDirection.FROM_END, n(2)), 2, 2, "bc");
        result(g(FIND, "abc", "ab", SearchDirection.FROM_END, n(1)), 1, 2, "ab");
        result(g(FIND, "a1a2a3", "a.", SearchDirection.FROM_END, UNDEFINED, n(2)), 3, 2, "a2");
        result(g(FIND, "ababa", "aba", UNDEFINED, UNDEFINED, n(2)), 0, 0, "");
        result(g(FIND, "abc", "(?<=a)b", UNDEFINED, n(2)), 2, 1, "b");
        result(g(FIND, "abc", "^b", UNDEFINED, n(2)), 0, 0, "");
        result(g(FIND, "abc", "b", UNDEFINED, new BigDecimal("1.9")), 2, 1, "b");
        result(g(FIND, "aa", "a", UNDEFINED, UNDEFINED, new BigDecimal("1.9")), 1, 1, "a");
        Object optional = c(g(FIND, "b", "(a)?(b)"), "GetGroups");
        result(c(optional, "Get", n(0)), 0, 0, "");
        result(c(c(g(FIND, "b", "()(b)"), "GetGroups"), "Get", n(0)), 1, 0, "");
        check(g(REPLACE, "a", "a", "$$").equals("$$"), "literal dollars");
        check(g(REPLACE, "a", "(a)", "${1}").equals("${1}"), "braced reference is literal");
        check(g(REPLACE, "a", "(a)", "\\$1").equals("$1"), "escaped dollar");
        check(g(REPLACE, "a", "a", "\\n").equals("n"), "escape removes backslash");
        check(g(REPLACE, "a", "(a)", "$12").equals("a2"), "longest valid group number");
        check(g(REPLACE, "a", "a*", "X").equals("XX"), "replace empty end match");
        check(g(REPLACE, "abc", "", "X").equals("XaXbXcX"), "replace accepts empty pattern");
        check(g(REPLACE, "", "a*", "X").equals("X"), "replace accepts empty source");
        check(g(REPLACE, "a", "(a)", "$01").equals("a1"), "group zero consumes exactly one digit");
        check(g(REPLACE, "a", "(a)", "$000").equals("a00"), "remaining zero digits are literal");
        fails(() -> g(REPLACE, "a", "(a)", "$99"));
        fails(() -> g(REPLACE, "z", "(a)", "$99"));
        fails(() -> g(REPLACE, "z", "a", "\\"));
        check(g(REPLACE, "a", "(a)", "$0").equals("a"), "invalid replacement cannot poison the pattern cache");
        result(g(FIND, "aaaa", "a+", SearchDirection.FROM_END), 4, 1, "a");
        result(g(FIND, "aaaa", "a+", SearchDirection.FROM_END, UNDEFINED, n(2)), 0, 0, "");
        result(g(FIND, "abc12def34", "(\\d+)", SearchDirection.FROM_END, UNDEFINED, n(2)), 5, 1, "2");
        check(c(g(ALL, "", "a", "ignored invalid flag"), "Count").equals(n(0)), "empty source bypasses optional validation");
        result(g(FIND, "", "[", UNDEFINED, n(0)), 0, 0, "");
        fails(() -> g(LIKE, "", "["));
        fails(() -> g(LIKE, "a", "(?<part>a)"));
        check(g(LIKE, "(?<part>a)", "\\Q(?<part>a)\\E").equals(true), "named-group-looking quoted literal");
    }
    static void negative() {
        for (String name : List.of(FIND, ALL, REPLACE, LIKE)) {
            fails(() -> g(name));
            fails(() -> g(name, n(1), "a", "x"));
        }
        RuntimeException invalid = fails(() -> g(LIKE, "a", "["));
        check(invalid.getMessage().contains("позиция") && invalid.getCause() instanceof java.util.regex.PatternSyntaxException,
            "pattern syntax position and cause");
        fails(() -> g(ALL, "a", ".", "true"));
        fails(() -> g(FIND, "abc", "b", "FromBegin"));
        fails(() -> g(FIND, "abc", "b", UNDEFINED, n(0)));
        fails(() -> g(FIND, "abc", "b", UNDEFINED, n(4)));
        fails(() -> g(FIND, "abc", "b", UNDEFINED, n(1), n(0)));
        fails(() -> g(LIKE, "a".repeat(16 * 1024 * 1024 + 1), ".*"));
        fails(() -> g(LIKE, "a", "a".repeat(8193)));
        fails(() -> g(ALL, "a", "(a)".repeat(257)));
        fails(() -> g(ALL, "a".repeat(100_001), "a"));
        fails(() -> g(REPLACE, "aa", "a", "b".repeat(8 * 1024 * 1024 + 1)));
        RuntimeException deep = fails(() -> g(LIKE, "a", "(".repeat(4000) + "a" + ")".repeat(4000)));
        check(deep instanceof BslException, "compile recursion becomes BSL error");
        Thread.currentThread().interrupt();
        try {
            try { g(LIKE, "a", "a"); throw new AssertionError("cancelled regex accepted"); }
            catch (Execution.Cancelled expected) { }
        } finally { Thread.interrupted(); }
    }
    static void resources() throws Exception {
        RuntimeException bad = fails(() -> g(LIKE, "a".repeat(10_000) + "!", "(a+)+$"));
        check(bad.getMessage().contains("лимит работы") || bad.getMessage().contains("слишком глубокий"), bad);
        // A permitted input's simple linear scan must not exhaust a fixed small budget.
        check(g(LIKE, "a".repeat(16 * 1024 * 1024), "a+").equals(true), "16 Mi UTF-16 linear input");
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                int index = i;
                calls.add(() -> g(LIKE, "v" + index, "v" + index).equals(true));
            }
            for (Future<Boolean> future : workers.invokeAll(calls)) check(future.get(), "concurrent cache result");
        } finally { workers.shutdownNow(); }
        var field = Class.forName("juno.runtime.RegexLibrary").getDeclaredField("CACHE");
        field.setAccessible(true);
        Map<?,?> cache = (Map<?,?>) field.get(null);
        synchronized (cache) { check(cache.size() <= 64, "bounded compiled pattern cache"); }
        check(g(LIKE, "abc", "ABC", true).equals(true) && g(LIKE, "abc", "ABC", false).equals(false), "cache flags are part of identity");
    }
    record Result(int code, String out, String err) {}
    static Result cli(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = Main.execute(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
    static void cli() throws Exception {
        Path root = Files.createTempDirectory("juno-regex-cli-");
        try {
            Path entry = Files.writeString(root.resolve("Main.bsl"), "СтрПодобнаПоРегулярномуВыражению(\"abc\",\"[\");");
            Result r = cli("run", entry.toString());
            check(r.code == 3 && r.out.isEmpty() && r.err.contains("Main.bsl:1") && r.err.contains("позиция"), r);
            Path tests = Files.writeString(root.resolve("Check.bsl"), "Процедура ТестШаблон() Экспорт СтрПодобнаПоРегулярномуВыражению(\"a\",\"[\"); КонецПроцедуры");
            r = cli("test", tests.toString(), "--format", "json");
            check(r.code == 1 && r.out.startsWith("[") && r.out.contains("PatternSyntaxException") && r.out.contains("Check.bsl"), r);
        } finally {
            try (var paths = Files.walk(root)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--deep")) {
            RuntimeException deep = fails(() -> g(LIKE, "a", "(".repeat(4000) + "a" + ")".repeat(4000)));
            Throwable cause = deep.getCause();
            check(deep instanceof BslException && (cause instanceof StackOverflowError
                || cause instanceof java.util.regex.PatternSyntaxException syntax
                    && syntax.getDescription().contains("Stack overflow")), "available JDK recursion cause");
            return;
        }
        basics(); platformEdges(); negative(); resources(); cli();
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process lowStack = new ProcessBuilder(java, "-Xss256k", "-cp", System.getProperty("java.class.path"),
            "RegexLibraryTests", "--deep").inheritIO().start();
        check(lowStack.waitFor() == 0, "low-stack pattern compile preserves StackOverflowError cause");
        System.out.println("Regex library PASS");
    }
}
