package juno.runtime;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/**
 * The text of a date in JSON as 1C 8.3.27.1606 writes and reads it. The local time zone is the one of
 * the machine, as in the platform; the empty date is never shifted when it is written.
 */
final class JsonDates {
    private JsonDates() {}
    enum Format { ISO, JAVASCRIPT, MICROSOFT }
    enum Variant { LOCAL, LOCAL_WITH_OFFSET, UNIVERSAL }
    static final Format DEFAULT_FORMAT = Format.ISO;
    static final Variant DEFAULT_VARIANT = Variant.LOCAL;
    private static final LocalDateTime FIRST = LocalDateTime.of(1, 1, 1, 0, 0, 0), LAST = LocalDateTime.of(9999, 12, 31, 23, 59, 59);
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss", Locale.ROOT);
    /** Date, then an optional time of 1-3 parts; a zone follows only a time with seconds. */
    private static final Pattern ISO = Pattern.compile(
        "\\s*(\\d{4})(-?)(\\d{2})\\2(\\d{2})(?:T(\\d{2})(?:(:?)(\\d{2})(?:\\6(\\d{2})(?:\\.\\d+)?(?:\\s*(Z|[+-]\\d{2}(?::?\\d{2})?))?)?)?)?\\s*");
    private static final Pattern JAVASCRIPT = Pattern.compile("new Date\\((-?\\d{1,18})\\)");
    private static final Pattern MICROSOFT = Pattern.compile("/Date\\((-?\\d{1,18})\\)/");

    static Format format(Object value) {
        return switch (IoEnums.code(value, JsonOptions.DATES)) {
            case "ISO" -> Format.ISO;
            case "JavaScript" -> Format.JAVASCRIPT;
            default -> Format.MICROSOFT;
        };
    }

    static Variant variant(Object value) {
        return switch (IoEnums.code(value, JsonOptions.DATE_VARIANT)) {
            case "LocalDate" -> Variant.LOCAL;
            case "LocalDateWithOffset" -> Variant.LOCAL_WITH_OFFSET;
            default -> Variant.UNIVERSAL;
        };
    }

    static String write(Object value, Format format, Variant variant) {
        if (!(value instanceof LocalDateTime date)) throw error("ЗаписатьДатуJSON ожидает Дата");
        BslDates.checked(date);
        if (date.getNano() != 0) throw error("JSON: дробные секунды даты не поддерживаются без потери точности");
        if (variant != Variant.UNIVERSAL && format != Format.ISO)
            throw error("Невозможно сохранить локальную дату в формате " + (format == Format.JAVASCRIPT ? "JavaScript" : "Microsoft"));
        boolean empty = date.equals(FIRST);
        switch (variant) {
            case LOCAL: return LOCAL.format(date);
            case LOCAL_WITH_OFFSET: return LOCAL.format(date) + offset(empty ? ZoneOffset.UTC : ZoneId.systemDefault().getRules().getOffset(date));
            default:
                LocalDateTime universal = empty ? date : date.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
                if (universal.getYear() < 1 || universal.getYear() > 9999) throw error("JSON: универсальная дата вне диапазона 0001..9999");
                long millis = universal.toEpochSecond(ZoneOffset.UTC) * 1000;
                return switch (format) {
                    case ISO -> LOCAL.format(universal) + "Z";
                    case JAVASCRIPT -> "new Date(" + millis + ")";
                    case MICROSOFT -> "/Date(" + millis + ")/";
                };
        }
    }

    private static String offset(ZoneOffset offset) {
        int minutes = offset.getTotalSeconds() / 60;
        return (minutes < 0 ? "-" : "+") + String.format(Locale.ROOT, "%02d:%02d", Math.abs(minutes) / 60, Math.abs(minutes) % 60);
    }

    static LocalDateTime read(Object value, Format format) {
        String text = value instanceof String s ? s : String.valueOf(value);
        LocalDateTime result = value instanceof String ? parse(text, format) : null;
        if (result == null) throw error("Представление даты имеет неверный формат ('" + text + "')");
        return result;
    }

    private static LocalDateTime parse(String text, Format format) {
        try {
            if (format == Format.ISO) return iso(text);
            Matcher matcher = (format == Format.JAVASCRIPT ? JAVASCRIPT : MICROSOFT).matcher(text);
            if (!matcher.matches()) return null;
            return local(Instant.ofEpochSecond(Math.floorDiv(Long.parseLong(matcher.group(1)), 1000L)));
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }

    private static LocalDateTime iso(String text) {
        Matcher m = ISO.matcher(text);
        if (!m.matches()) return null;
        int year = Integer.parseInt(m.group(1));
        if (year < 1) return null;
        int hour = m.group(5) == null ? 0 : Integer.parseInt(m.group(5));
        int minute = m.group(7) == null ? 0 : Integer.parseInt(m.group(7));
        int second = m.group(8) == null ? 0 : Integer.parseInt(m.group(8));
        LocalDateTime date = LocalDateTime.of(year, Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), hour, minute, second);
        String zone = m.group(9);
        if (zone == null) return date;
        int seconds = 0; // the platform accepts offsets up to +-24:00, wider than ZoneOffset
        if (!zone.equals("Z")) {
            String digits = zone.substring(1).replace(":", "");
            int hours = Integer.parseInt(digits.substring(0, 2)), minutes = digits.length() > 2 ? Integer.parseInt(digits.substring(2)) : 0;
            if (hours > 24 || minutes > 59) return null;
            seconds = hours * 3600 + minutes * 60;
            if (zone.charAt(0) == '-') seconds = -seconds;
        }
        return local(Instant.ofEpochSecond(date.toEpochSecond(ZoneOffset.UTC) - seconds));
    }

    /** The local time of an instant, kept inside the range of a 1C date. */
    private static LocalDateTime local(Instant instant) {
        LocalDateTime local = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        return local.isBefore(FIRST) ? FIRST : local.isAfter(LAST) ? LAST : local;
    }
}
