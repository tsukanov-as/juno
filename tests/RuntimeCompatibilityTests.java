import java.math.BigDecimal;
import java.time.LocalDateTime;
import juno.runtime.RuntimeSupport;
import juno.runtime.ValueCopy;
import static juno.runtime.RuntimeSupport.*;

/** Old compiled modules retain the dispatcher/operator ABI alongside specialized entry points. */
public final class RuntimeCompatibilityTests {
    static void check(boolean ok, String context) { if (!ok) throw new AssertionError(context); }
    static BigDecimal n(String value) { return new BigDecimal(value); }
    static void numeric(Object actual, String expected) { check(decimal(actual).compareTo(n(expected)) == 0, "Expected " + expected + ", got " + actual); }
    static void fails(Runnable action) {
        try { action.run(); } catch (BslException expected) { return; }
        throw new AssertionError("Expected a BSL error");
    }

    public static void main(String[] args) {
        LocalDateTime first = LocalDateTime.of(1, 1, 1, 0, 0), last = LocalDateTime.of(2026, 1, 1, 0, 0);
        numeric(binary(last, first, "ЗнакВычитания"), "63902822400");
        numeric(binary(first, last, "ЗнакВычитания"), "-63902822400");
        check(binary(first, n("63902822400"), "ЗнакСложения").equals(last), "legacy date addition");
        check(binary(last, n("63902822400"), "ЗнакВычитания").equals(first), "legacy date subtraction");
        numeric(binary(last.plusNanos(1), first, "ЗнакВычитания"), "63902822400.000000001");
        numeric(binary(first, last.plusNanos(1), "ЗнакВычитания"), "-63902822400.000000001");
        // Inputs beyond long seconds must fail instead of silently wrapping their nanoseconds.
        try { add(first, n("1000000000000000000000")); throw new AssertionError("overflow accepted"); }
        catch (ArithmeticException expected) {}

        numeric(global("Round", new Object[]{n("-1.5"), n("0"), n("0")}), "-1");
        numeric(globalNamed("окр", new Object[]{n("1.5"), n("0"), n("1")}), "2");
        fails(() -> roundNumber(n("1.5"), UNDEFINED));
        fails(() -> globalNamed("окр", new Object[]{n("1.5"), UNDEFINED}));
        fails(() -> globalNamed("окр", new Object[]{n("1.5"), n("0"), UNDEFINED}));

        check(global("TrimAll", new Object[]{"\u00a0 x\u00a0"}).equals("x"), "legacy NBSP trim");
        check(globalNamed("пустаястрока", new Object[]{"\u00a0"}).equals(true), "legacy NBSP blank");

        Object directions = globalValue("searchdirection");
        Object back = getPropertyNamed(directions, "fromend");
        check(ValueCopy.snapshot(back) == back, "enum value survives background parameter transport");
        check(ValueCopy.snapshot(directions) == directions, "immutable enum namespace survives transport");
        numeric(globalNamed("strfind", new Object[]{"abcabc", "bc", back, n("5")}), "2");
        numeric(globalNamed("стрнайти", new Object[]{"aaaaa", "aa", Special.MISSING, Special.MISSING, n("3")}), "3");
        fails(() -> strFind("abc", "a", n("1")));

        Object map = construct("Map", new Object[0]);
        call(map, "insert", new Object[]{"key", n("7")});
        Object pair = next(iterator(map));
        check(getPropertyNamed(pair, "key").equals("key"), "entry fast property");
        numeric(read(propertyNamed(pair, "value")), "7");
        System.out.println("Runtime compatibility PASS");
    }
}
