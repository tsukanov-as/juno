package juno.runtime;

import java.util.*;

/**
 * Argument counts of the global functions that RuntimeSupport.globalNamed dispatches by name.
 * Limits are what 1C 8.3.27.1606 accepts: each other count is a compile error there, never ignored.
 */
final class LegacyArity {
    private LegacyArity() {}
    private record Limits(String name, int min, int max) {}
    private static final int ANY = Integer.MAX_VALUE;
    private static final Map<String, Limits> LIMITS = create();

    private static Map<String, Limits> create() {
        Object[][] table = {
            {"Строка", "String", 1, 1}, {"Число", "Number", 1, 1}, {"Тип", "Type", 1, 1}, {"ТипЗнч", "TypeOf", 1, 1},
            {"СтрДлина", "StrLen", 1, 1}, {"Сред", "Mid", 2, 3}, {"Лев", "Left", 2, 2}, {"Прав", "Right", 2, 2},
            {"СокрЛП", "TrimAll", 1, 1}, {"ПустаяСтрока", "IsBlankString", 1, 1}, {"НРег", "Lower", 1, 1}, {"ВРег", "Upper", 1, 1},
            {"СтрЗаменить", "StrReplace", 3, 3}, {"СтрНачинаетсяС", "StrStartsWith", 2, 2}, {"СтрНайти", "StrFind", 2, 5},
            {"СтрРазделить", "StrSplit", 2, 3}, {"СтрСоединить", "StrConcat", 1, 2}, {"СтрШаблон", "StrTemplate", 1, 11},
            {"Мин", "Min", 1, ANY}, {"Макс", "Max", 1, ANY}, {"Цел", "Int", 1, 1}, {"Окр", "Round", 1, 3},
            {"Символ", "Char", 1, 1}, {"КодСимвола", "CharCode", 1, 2},
            // 1 or 3 or 6 in 1C; the exact set is checked where the date is built.
            {"Дата", "Date", 1, 6},
            {"Сообщить", "Message", 1, 2}, {"ОписаниеОшибки", "ErrorDescription", 0, 0}, {"ИнформацияОбОшибке", "ErrorInfo", 0, 0},
            {"ПодробноеПредставлениеОшибки", "DetailErrorDescription", 1, 1}, {"КраткоеПредставлениеОшибки", "BriefErrorDescription", 1, 1},
            {"ТекущаяУниверсальнаяДатаВМиллисекундах", "", 0, 0},
            // Not in 1C: OneScript names and juno extensions.
            {"ЗавершитьРаботу", "Exit", 0, 1}, {"ПроверитьОтменуФоновогоЗадания", "CheckBackgroundCancellation", 0, 0},
            {"ПолучитьДвоичныеДанныеИзHexСтроки", "", 1, 1},
        };
        Map<String, Limits> limits = new HashMap<>();
        for (Object[] row : table) {
            Limits entry = new Limits((String) row[0], (int) row[2], (int) row[3]);
            limits.put(((String) row[0]).toLowerCase(Locale.ROOT), entry);
            if (!((String) row[1]).isEmpty()) limits.put(((String) row[1]).toLowerCase(Locale.ROOT), entry);
        }
        return Map.copyOf(limits);
    }

    static Map<String, GlobalFunctions.Signature> signatures() {
        Map<String,GlobalFunctions.Signature> result = new LinkedHashMap<>();
        LIMITS.forEach((name,limits) -> result.put(name,new GlobalFunctions.Signature(limits.name, name, limits.min, limits.max)));
        return result;
    }
    /** Names are the lower-case keys of globalNamed; unknown names are left to its own "unsupported" error. */
    static void check(String name, int count) {
        Limits limits = LIMITS.get(name);
        if (limits == null || count >= limits.min && count <= limits.max) return;
        throw new RuntimeSupport.BslException(limits.name + ": ожидалось аргументов " + limits.min
            + (limits.max == limits.min ? "" : ".." + (limits.max == ANY ? "" : limits.max)) + ", получено " + count);
    }
}
