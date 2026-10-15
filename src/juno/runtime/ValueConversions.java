package juno.runtime;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.*;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.regex.Pattern;
import static juno.runtime.RuntimeSupport.*;

/** Primitive conversions. XML lexical values never pass through localized presentation. */
final class ValueConversions {
    private ValueConversions() {}
    private static final Pattern XML_DECIMAL = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)");
    private static final Pattern XML_DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}(?:T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?)?");
    private static final DateTimeFormatter XML_DATE_TIME = new DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd'T'HH:mm:ss").appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
        .toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    static Object bool(Object value) {
        if (value instanceof Boolean || value instanceof BigDecimal) return truth(value);
        if (value instanceof String s) return switch (s.toLowerCase(Locale.ROOT)) {
            case "истина", "да", "true" -> true;
            case "ложь", "нет", "false" -> false;
            default -> throw new BslException("Преобразование к типу Булево не поддерживается");
        };
        throw new BslException("Преобразование к типу Булево не поддерживается");
    }
    static Object filled(Object value) {
        if (value == null || value == UNDEFINED || value == Special.MISSING || value == Special.NULL) return false;
        if (value instanceof BigDecimal n) return n.signum() != 0;
        if (value instanceof String) return !((Boolean) isBlankString(value));
        if (value instanceof LocalDateTime d) return !d.equals(BslDates.EMPTY);
        if (value instanceof java.util.UUID id) return !id.equals(HelperLibrary.ZERO_UUID);
        if (value instanceof BArray a) return !a.values.isEmpty();
        if (value instanceof Struct s) return !s.values.isEmpty();
        if (value instanceof BMap m) return !m.values.isEmpty();
        if (value instanceof Table t) return !t.rows.isEmpty();
        if (value instanceof Columns c) return !c.values.isEmpty();
        if (value instanceof Binary b) return b.bytes.length != 0;
        return true; // False is itself a filled Boolean value.
    }
    static Object xmlString(Object value) {
        if (value == null || value == UNDEFINED || value == Special.NULL) return "";
        if (value instanceof String s) return s;
        if (value instanceof java.util.UUID id) return id.toString();
        if (value instanceof BigDecimal n) return n.stripTrailingZeros().toPlainString();
        if (value instanceof Boolean b) return b ? "true" : "false";
        if (value instanceof LocalDateTime d) return BslDates.checked(d).format(XML_DATE_TIME);
        throw new BslException("XMLСтрока: неподдерживаемый тип примитива");
    }
    static Object xmlValue(Object target, Object value) {
        if (!(target instanceof Type)) throw new BslException("XMLЗначение: первый аргумент должен иметь тип Тип");
        String text = string(value);
        if (target.equals(type("Строка"))) return text; // This API converts lexical content; it does not parse XML markup.
        if (target.equals(type("Неопределено")) || target.equals(type("Null"))) {
            if (!text.isEmpty()) throw new BslException("XMLЗначение: ожидалась пустая строка");
            return target.equals(type("Null")) ? Special.NULL : UNDEFINED;
        }
        if (target.equals(type("UUID"))) return HelperLibrary.uuid(text);
        String lexical = text.strip();
        if (target.equals(type("Число"))) {
            if (!XML_DECIMAL.matcher(lexical).matches()) throw new BslException("XMLЗначение: неверная лексическая форма Число");
            return new BigDecimal(lexical);
        }
        if (target.equals(type("Булево"))) return switch (lexical) {
            case "true", "1" -> true; case "false", "0" -> false;
            default -> throw new BslException("XMLЗначение: неверная лексическая форма Булево");
        };
        if (target.equals(type("Дата"))) {
            // BSL dates have no offset. Reject zones instead of silently losing their meaning.
            if (!XML_DATE.matcher(lexical).matches()) throw new BslException("XMLЗначение: ожидается дата ISO без часового пояса, до 9 знаков долей секунды");
            try {
                return BslDates.checked(lexical.length() == 10 ? LocalDate.parse(lexical).atStartOfDay() : LocalDateTime.parse(lexical, XML_DATE_TIME));
            } catch (DateTimeException e) { throw new BslException("XMLЗначение: неверная дата"); }
        }
        throw new BslException("XMLЗначение: неподдерживаемый тип примитива");
    }
}
