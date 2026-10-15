import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import juno.runtime.GlobalFunctions;
import static juno.runtime.RuntimeSupport.*;

/** Boundaries that need a JVM clock/locale, registry metadata, or old compiled-module ABI. */
public final class ValueLibraryTests {
    static void check(boolean value, Object context) { if (!value) throw new AssertionError(context); }
    static void fails(Runnable body) {
        try { body.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError("Expected an error");
    }
    static Object invoke(String name, Object... arguments) { return global(name, arguments); }
    static void substrings() {
        for (int position : new int[]{Integer.MIN_VALUE, -1, 0, 1, 2, 3, 4, Integer.MAX_VALUE}) {
            int start = (int)Math.max(0L, (long)position - 1);
            String tail = start >= 3 ? "" : "abc".substring(start);
            BigDecimal p = BigDecimal.valueOf(position);
            check(mid("abc", p).equals(tail), "Mid position " + position);
            check(invoke("Сред", "abc", p).equals(tail), "dispatcher position " + position);
            for (int length : new int[]{Integer.MIN_VALUE, -1, 0, 1, 2, Integer.MAX_VALUE}) {
                String expected = length < 0 ? tail : tail.substring(0, Math.min(tail.length(), length));
                BigDecimal n = BigDecimal.valueOf(length);
                check(mid("abc", p, n).equals(expected), "Mid position/length " + position + "/" + length);
                check(invoke("Mid", "abc", p, n).equals(expected), "dispatcher position/length " + position + "/" + length);
            }
        }
        check(mid("", BigDecimal.valueOf(Integer.MIN_VALUE), BigDecimal.valueOf(Integer.MAX_VALUE)).equals(""), "empty substring");
    }
    public static void main(String[] args) {
        substrings();
        check(invoke("XMLString", new BigDecimal("1.50")).equals("1.5"), "XML decimal scale");
        check(invoke("StrReplace", "abc", "", "x").equals("abc"), "empty replacement needle");
        check(invoke("Upper", "straße").equals("STRAßE"), "one UTF16 unit upper case");
        check(invoke("Char",new BigDecimal("128512")).equals(""), "character range");
        check(invoke("CharCode", "").equals(new BigDecimal("-1")), "empty character code");
        check(invoke("XMLString",invoke("Pow",new BigDecimal("2"),new BigDecimal("100"))).equals("1267650600228229401496703205376"), "integer power exact");
        check(invoke("XMLString",divide(new BigDecimal("200"),new BigDecimal("3"))).equals("66.666666666666666666666666667"), "division precision");
        check(invoke("Format",new BigDecimal("-0.004"),"ЧДЦ=2").equals("0,00"),"rounded nonzero");
        check(invoke("Format",new BigDecimal("1.50"),"").equals("1,5"),"format scale");
        check(invoke("Format",new BigDecimal("1234.50"),"ЧЦ=3;ЧДЦ=2").equals("9,99"),"format digit overflow");
        check(invoke("Format",new BigDecimal("0"),"ЧН=-").equals("-"),"zero presentation");
        check(invoke("Format",LocalDateTime.of(2026,1,5,7,8,9),"ДФ=ММММ").equals("Январь"),"standalone month");
        // Every new alias rejects extra arguments before attempting value conversion.
        Set<String> aliases = new HashSet<>();
        for (var signature : GlobalFunctions.signatures()) {
            for (String alias : new LinkedHashSet<>(List.of(signature.russianName().toLowerCase(Locale.ROOT), signature.englishName().toLowerCase(Locale.ROOT)))) {
                check(aliases.add(alias.toLowerCase(Locale.ROOT)), alias);
                Object[] tooMany = new Object[signature.maxArguments() + 1];
                Arrays.fill(tooMany, UNDEFINED);
                fails(() -> invoke(alias, tooMany));
                if (signature.minArguments() > 0) {
                    fails(() -> invoke(alias));
                    Object[] missing = new Object[signature.minArguments()];
                    Arrays.fill(missing, Special.MISSING);
                    fails(() -> invoke(alias, missing));
                }
            }
        }
        // The legacy dispatcher snapshots reference arguments before conversion.
        Cell value = new Cell(new BigDecimal("1234.500"));
        check(invoke("Format", value, "ЧГ=0;ЧРД=.;ЧДЦ=2").equals("1234.50"), "reference arguments");
        LocalDateTime d = LocalDateTime.of(2024, 2, 29, 13, 4, 5, 123456789);
        check(invoke("XMLValue", type("Date"), invoke("XMLString", d)).equals(d), "lossless date lexical round trip");
        check(invoke("XMLString", new BigDecimal("1E+40")).equals("10000000000000000000000000000000000000000"), "XML decimal is not exponential");
        check(invoke("BegOfDay", d).equals(LocalDateTime.of(2024, 2, 29, 0, 0)), "English calendar alias");
        check(invoke("EndOfMonth", d).equals(LocalDateTime.of(2024, 2, 29, 23, 59, 59)), "English month alias");
        check(decimal(invoke("Day", d)).intValueExact() == 29, "English date part");
        check(decimal(invoke("Minute", d)).intValueExact() == 4, "English minute part");

        Locale previousLocale = Locale.getDefault();
        TimeZone previousZone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.US);
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+03:00"));
            check(invoke("Format", new BigDecimal("1234.5"), "").equals("1\u00a0234,5"), "explicit ru formatting is locale-independent");
            check(string(new BigDecimal("1234.5")).equals("1234.5"), "base numeric presentation preserved");
            check(invoke("XMLString", d).equals("2024-02-29T13:04:05.123456789"), "XML ignores human locale and zone");
            LocalDateTime local = (LocalDateTime) invoke("CurrentDate");
            LocalDateTime utc = (LocalDateTime) invoke("CurrentUniversalDate");
            check(Duration.between(local, LocalDateTime.now()).abs().getSeconds() <= 1, "local clock follows system zone");
            check(Duration.between(utc, LocalDateTime.now(ZoneOffset.UTC)).abs().getSeconds() <= 1, "UTC clock");
            check(local.getNano() == 0 && utc.getNano() == 0, "clock returns whole seconds");
        } finally { Locale.setDefault(previousLocale); TimeZone.setDefault(previousZone); }

        check(date("'2024-02-29 13:04:05'").equals(d.withNano(0)), "quoted literal ABI");
        fails(() -> date("abc20240229"));
        fails(() -> date("2024-02-29T13:04:05"));
        fails(() -> date("00000101"));
        fails(() -> global("Date", new Object[]{new BigDecimal("10000"), BigDecimal.ONE, BigDecimal.ONE}));
        fails(() -> binary(LocalDateTime.of(1, 1, 1, 0, 0), BigDecimal.ONE, "ЗнакВычитания"));
        fails(() -> binary(LocalDateTime.of(9999, 12, 31, 23, 59, 59), BigDecimal.ONE, "ЗнакСложения"));
        System.out.println("Value library PASS");
    }
}
