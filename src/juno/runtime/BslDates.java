package juno.runtime;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import static juno.runtime.RuntimeSupport.*;

/** Calendar operations for the BSL range 0001..9999, independent of presentation. */
final class BslDates {
    private BslDates() {}
    static final LocalDateTime EMPTY = LocalDateTime.of(1, 1, 1, 0, 0);
    private static final DateTimeFormatter PRESENTATION = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss", Locale.ROOT);
    static LocalDateTime checked(LocalDateTime value) {
        if (value.getYear() < 1 || value.getYear() > 9999) throw new BslException("Дата вне диапазона 0001..9999");
        return value;
    }
    static LocalDateTime value(Object value) {
        if (value instanceof LocalDateTime d) return checked(d);
        if (value instanceof String s) return checked((LocalDateTime) date(s));
        throw new BslException("Преобразование к типу Дата не поддерживается");
    }
    static String presentation(LocalDateTime value) { return checked(value).format(PRESENTATION); }
    static Object part(Object value, String part) {
        LocalDateTime d = value(value);
        return number(Integer.toString(switch (part) {
            case "year" -> d.getYear(); case "month" -> d.getMonthValue(); case "day" -> d.getDayOfMonth();
            case "hour" -> d.getHour(); case "minute" -> d.getMinute(); case "second" -> d.getSecond();
            default -> throw new IllegalArgumentException(part);
        }));
    }
    static Object boundary(Object value, boolean month, boolean end) {
        LocalDateTime d = value(value);
        LocalDate day = month ? d.toLocalDate().withDayOfMonth(end ? d.toLocalDate().lengthOfMonth() : 1) : d.toLocalDate();
        return day.atTime(end ? LocalTime.of(23, 59, 59) : LocalTime.MIDNIGHT);
    }
    static Object boundary(Object value, String unit, boolean end) {
        LocalDateTime date = value(value);
        LocalDate day = date.toLocalDate();
        return switch (unit) {
            case "Year" -> LocalDate.of(date.getYear(), end ? 12 : 1, end ? 31 : 1).atTime(end ? LocalTime.of(23, 59, 59) : LocalTime.MIDNIGHT);
            case "Quarter" -> {
                int month = ((date.getMonthValue() - 1) / 3) * 3 + 1;
                LocalDate first = LocalDate.of(date.getYear(), month, 1);
                LocalDate last = first.withMonth(month + 2); last = last.withDayOfMonth(last.lengthOfMonth());
                yield end ? last.atTime(23, 59, 59) : first.atStartOfDay();
            }
            case "Week" -> {
                LocalDate first = day.minusDays(day.getDayOfWeek().getValue() - 1);
                LocalDate last = first.plusDays(6), max = LocalDate.of(9999, 12, 31);
                if (last.isAfter(max)) last = max;
                yield end ? last.atTime(23, 59, 59) : first.atStartOfDay();
            }
            case "Hour" -> date.withMinute(end ? 59 : 0).withSecond(end ? 59 : 0).withNano(0);
            case "Minute" -> date.withSecond(end ? 59 : 0).withNano(0);
            default -> throw new IllegalArgumentException(unit);
        };
    }
    static Object calendarPart(Object value, String part) {
        LocalDateTime date = value(value);
        int number = switch (part) {
            case "day" -> date.getDayOfYear();
            case "weekday" -> date.getDayOfWeek().getValue();
            case "week" -> {
                LocalDate first = LocalDate.of(date.getYear(), 1, 1);
                yield (date.getDayOfYear() + first.getDayOfWeek().getValue() - 2) / 7 + 1;
            }
            default -> throw new IllegalArgumentException(part);
        };
        return number(Integer.toString(number));
    }
    static Object addMonths(Object value, Object months) {
        try { return checked(value(value).plusMonths(decimal(months).toBigInteger().longValueExact())); }
        catch (DateTimeException | ArithmeticException e) { throw new BslException("Неверное смещение месяца или дата вне диапазона"); }
    }
    static Object convertZone(Object[] args,boolean utc) {
        LocalDateTime date=value(args[0]);
        try {
            Object supplied=IoSupport.optional(args,1,"");String name=string(supplied);
            ZoneId zone=name.isEmpty()?ZoneId.systemDefault():ZoneId.of(name);
            return checked(utc?LocalDateTime.ofInstant(date.atZone(zone).toInstant(),ZoneOffset.UTC):LocalDateTime.ofInstant(date.toInstant(ZoneOffset.UTC),zone));
        }catch(DateTimeException e){throw new BslException("Неверный часовой пояс или дата вне диапазона");}
    }
    static Object now(boolean utc) { return LocalDateTime.now(utc ? ZoneOffset.UTC : ZoneId.systemDefault()).withNano(0); }
}
