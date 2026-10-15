package juno.runtime;

import java.math.*;
import java.util.*;
import java.util.function.DoubleUnaryOperator;
import java.util.regex.Pattern;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Ordinary computed helpers; no platform services or dynamic host reflection. */
final class HelperLibrary {
    private HelperLibrary() {}
    static final UUID ZERO_UUID = new UUID(0, 0);
    private static final Set<String> CONSTRUCTORS = Set.of("уникальныйидентификатор", "uuid", "генераторслучайныхчисел", "randomnumbergenerator", "хешированиеданных", "datahashing");
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    static UUID uuid(Object value) {
        if (!(value instanceof String text) || !UUID_TEXT.matcher(text).matches()) throw error("УникальныйИдентификатор: ожидается UUID в форме 8-4-4-4-12 hex");
        return UUID.fromString(text);
    }
    static Object construct(String name, Object[] raw) {
        if (!CONSTRUCTORS.contains(name)) return null;
        Object[] args = values(raw);
        return switch (name) {
            case "уникальныйидентификатор", "uuid" -> {
                arity(name, args, 0, 1);
                yield args.length == 0 || omitted(args[0]) ? UUID.randomUUID() : uuid(args[0]);
            }
            case "генераторслучайныхчисел", "randomnumbergenerator" -> {
                arity(name, args, 0, 1);
                yield new Generator(optional(args, 0, UNDEFINED));
            }
            case "хешированиеданных", "datahashing" -> {
                arity(name, args, 1, 1); yield new DataHashing(args[0]);
            }
            default -> null;
        };
    }
    static void register(Map<String,GlobalFunctions.Entry> entries) {
        addMath(entries, "Sqrt", StrictMath::sqrt);
        addMath(entries, "Log", StrictMath::log);
        addMath(entries, "Log10", StrictMath::log10);
        addMath(entries, "Exp", StrictMath::exp);
        addMath(entries, "Sin", StrictMath::sin);
        addMath(entries, "Cos", StrictMath::cos);
        addMath(entries, "Tan", StrictMath::tan);
        addMath(entries, "ASin", StrictMath::asin);
        addMath(entries, "ACos", StrictMath::acos);
        addMath(entries, "ATan", StrictMath::atan);
        GlobalFunctions.add(entries, "Pow", "Pow", 2, 2, a -> power(a[0],a[1]));
        String[][] boundaries = {{"Года", "Year"}, {"Квартала", "Quarter"}, {"Недели", "Week"}, {"Часа", "Hour"}, {"Минуты", "Minute"}};
        for (String[] boundary : boundaries) {
            GlobalFunctions.add(entries, "Начало" + boundary[0], "BegOf" + boundary[1], 1, 1,
                a -> BslDates.boundary(a[0], boundary[1], false));
            GlobalFunctions.add(entries, "Конец" + boundary[0], "EndOf" + boundary[1], 1, 1,
                a -> BslDates.boundary(a[0], boundary[1], true));
        }
        GlobalFunctions.add(entries, "ДеньГода", "DayOfYear", 1, 1, a -> BslDates.calendarPart(a[0], "day"));
        GlobalFunctions.add(entries, "ДеньНедели", "WeekDay", 1, 1, a -> BslDates.calendarPart(a[0], "weekday"));
        GlobalFunctions.add(entries, "НеделяГода", "WeekOfYear", 1, 1, a -> BslDates.calendarPart(a[0], "week"));
        GlobalFunctions.add(entries, "ЗаполнитьЗначенияСвойств", "FillPropertyValues", 2, 4, HelperLibrary::fillProperties);
    }
    private static Object power(Object base,Object exponent) {
        BigDecimal x=decimal(base), y=decimal(exponent).stripTrailingZeros();
        if(y.scale()<=0 && y.abs().compareTo(BigDecimal.valueOf(100000))<=0) {
            int n=y.intValueExact(); BigDecimal result=x.pow(Math.abs(n));
            return n<0?divide(BigDecimal.ONE,result):result;
        }
        return finite("Pow", StrictMath.pow(real(base),real(exponent)));
    }
    private static void addMath(Map<String,GlobalFunctions.Entry> entries, String name, DoubleUnaryOperator body) {
        GlobalFunctions.add(entries, name, name, 1, 1, a -> finite(name, body.applyAsDouble(real(a[0]))));
    }
    private static double real(Object value) {
        double number = decimal(value).doubleValue();
        if (!Double.isFinite(number)) throw error("Математическая функция: аргумент вне диапазона double");
        return number;
    }
    private static BigDecimal finite(String operation, double value) {
        if (!Double.isFinite(value)) throw error(operation + ": значение вне области определения или переполнение");
        return BigDecimal.valueOf(value);
    }
    static final class Generator implements NativeObject, TypedValue {
        private static final long UINT_MAX = 0xffff_ffffL;
        private final Random random;
        Generator(Object seed) {
            if (omitted(seed)) random = new Random();
            else {
                if (!(seed instanceof BigDecimal number)) throw error("Начальное число генератора требует Число");
                random = new Random(number.longValue());
            }
        }
        private static long uint(Object value) {
            if (!(value instanceof BigDecimal number)) throw error("ГенераторСлучайныхЧисел требует Число");
            if (number.compareTo(BigDecimal.valueOf(-1)) <= 0 || number.compareTo(BigDecimal.valueOf(UINT_MAX + 1)) >= 0)
                throw error("Число вне диапазона 0..4294967295");
            return number.longValue();
        }
        public String bslTypeName() { return "ГенераторСлучайныхЧисел"; }
        public String toString() { return bslTypeName(); }
        public Object property(String name) { return missingProperty(bslTypeName(), name); }
        public Object invoke(String name, Object[] raw) {
            if (!name.equals("случайноечисло") && !name.equals("randomnumber")) return missingMethod(bslTypeName(), name);
            Object[] args = values(raw); arity(name, args, 0, 2);
            long lower = uint(optional(args, 0, BigDecimal.ZERO)), upper = uint(optional(args, 1, BigDecimal.valueOf(UINT_MAX)));
            if (upper < lower) throw error("Верхняя граница случайного числа меньше нижней");
            return BigDecimal.valueOf(lower + random.nextLong(upper - lower + 1));
        }
    }
    private static Map<String,Ref> properties(Object object, boolean destination) {
        Map<String,Ref> result = new LinkedHashMap<>();
        if (object instanceof Struct structure) {
            for (String name : structure.values.keySet()) result.put(name, destination && structure.fixed
                ? reference(() -> structure.values.get(name).value, value -> { })
                : (Ref) propertyNamed(structure, name));
        } else if (object instanceof Row row) {
            for (Column column : row.owner.columns.values) if (!column.name.isEmpty())
                result.put(column.normalizedName, reference(() -> row.get(column), value -> row.set(column, value)));
        } else if (object instanceof ValueTree.TreeRow row) {
            for (Column column : row.owner().columns.values) if (!column.name.isEmpty())
                result.put(column.normalizedName, reference(() -> row.get(column), value -> row.set(column, value)));
        } else if (object instanceof RuntimeSupport.Module module) result.putAll(moduleProperties(module));
        else throw error("ЗаполнитьЗначенияСвойств: поддерживаются Структура, строка таблицы/дерева и экспортные поля BSL-модуля");
        return result;
    }
    private static List<String> names(Object value) {
        if (omitted(value)) return List.of();
        if (!(value instanceof String text)) throw error("Список свойств требует Строка");
        if (text.isEmpty()) return List.of();
        List<String> names = new ArrayList<>();
        for (String part : text.split(",", -1)) if (!part.isBlank()) names.add(part.strip().toLowerCase(Locale.ROOT));
        return names;
    }
    private static Object fillProperties(Object[] args) {
        Map<String,Ref> destination = properties(args[0], true), source = properties(args[1], false);
        List<String> include = names(optional(args, 2, UNDEFINED));
        Set<String> selected = new LinkedHashSet<>();
        if (args.length > 2 && !omitted(args[2])) {
            for (String name : include) {
                if (!source.containsKey(name) || !destination.containsKey(name)) throw error("Не найдено свойство из списка: " + name);
                selected.add(name);
            }
        } else {
            selected.addAll(destination.keySet()); selected.retainAll(source.keySet());
            for (String name : names(optional(args, 3, UNDEFINED))) {
                if (!source.containsKey(name) || !destination.containsKey(name)) throw error("Не найдено исключаемое свойство: " + name);
                selected.remove(name);
            }
        }
        for (String name : selected) { Execution.checkCancellation(); destination.get(name).set(source.get(name).get()); }
        return UNDEFINED;
    }
}
