package juno.runtime;

import java.util.*;
import java.util.function.Function;
import static juno.runtime.RuntimeSupport.*;

/** Library extensions: one RU/EN signature and arity check per global function. */
public final class GlobalFunctions {
    private GlobalFunctions() {}
    public record Signature(String russianName, String englishName, int minArguments, int maxArguments) {}
    static final class Entry {
        final Signature signature;
        private final Function<Object[], Object> body;
        private final BitSet outputs;
        Entry(Signature signature, Function<Object[], Object> body) { this(signature,body,new BitSet()); }
        Entry(Signature signature,Function<Object[],Object> body,BitSet outputs) {this.signature=signature;this.body=body;this.outputs=(BitSet)outputs.clone();}
        Object invoke(Object[] arguments) {
            int count = arguments.length;
            if (count < signature.minArguments || count > signature.maxArguments)
                throw new BslException(signature.russianName + ": ожидалось аргументов " + signature.minArguments
                    + (signature.minArguments == signature.maxArguments ? "" : ".." + signature.maxArguments) + ", получено " + count);
            Object[] values = new Object[count];
            for (int i = 0; i < count; i++) {
                values[i] = outputs.get(i) ? arguments[i] : read(arguments[i]);
                if (i < signature.minArguments && values[i] == Special.MISSING) throw new BslException(signature.russianName + ": пропущен обязательный аргумент " + (i + 1));
            }
            return body.apply(values);
        }
    }
    private static final Map<String, Entry> FUNCTIONS = create();
    private static Map<String, Entry> create() {
        Map<String, Entry> entries = new LinkedHashMap<>();
        add(entries, "Формат", "Format", 2, a -> ValueFormat.format(a[0], a[1]));
        add(entries, "Булево", "Boolean", 1, a -> ValueConversions.bool(a[0]));
        add(entries, "ЗначениеЗаполнено", "ValueIsFilled", 1, a -> ValueConversions.filled(a[0]));
        add(entries, "XMLСтрока", "XMLString", 1, a -> ValueConversions.xmlString(a[0]));
        add(entries, "XMLЗначение", "XMLValue", 2, a -> ValueConversions.xmlValue(a[0], a[1]));
        String[][] parts = {{"Год", "Year"}, {"Месяц", "Month"}, {"День", "Day"}, {"Час", "Hour"}, {"Минута", "Minute"}, {"Секунда", "Second"}};
        for (String[] part : parts) add(entries, part[0], part[1], 1, a -> BslDates.part(a[0], part[1].toLowerCase(Locale.ROOT)));
        add(entries, "НачалоДня", "BegOfDay", 1, a -> BslDates.boundary(a[0], false, false));
        add(entries, "КонецДня", "EndOfDay", 1, a -> BslDates.boundary(a[0], false, true));
        add(entries, "НачалоМесяца", "BegOfMonth", 1, a -> BslDates.boundary(a[0], true, false));
        add(entries, "КонецМесяца", "EndOfMonth", 1, a -> BslDates.boundary(a[0], true, true));
        add(entries, "ДобавитьМесяц", "AddMonth", 2, a -> BslDates.addMonths(a[0], a[1]));
        add(entries, "ТекущаяДата", "CurrentDate", 0, a -> BslDates.now(false));
        add(entries, "ТекущаяУниверсальнаяДата", "CurrentUniversalDate", 0, a -> BslDates.now(true));
        add(entries, "СокрЛ", "TrimL", 1, a -> trimLeft(a[0]));
        add(entries, "СокрП", "TrimR", 1, a -> trimRight(a[0]));
        add(entries, "СтрСравнить", "StrCompare", 2, a -> BslStrings.compare(a[0], a[1]));
        add(entries, "СтрЗаканчиваетсяНа", "StrEndsWith", 2, a -> BslStrings.endsWith(a[0], a[1]));
        add(entries, "СтрЧислоСтрок", "StrLineCount", 1, a -> BslStrings.lineCount(a[0]));
        add(entries, "СтрПолучитьСтроку", "StrGetLine", 2, a -> BslStrings.getLine(a[0], a[1]));
        GlobalFunctions.add(entries,"СтрЧислоВхождений","StrOccurrenceCount",2,2,a -> BslStrings.occurrences(a[0],a[1]));
        GlobalFunctions.add(entries,"Найти","Find",2,2,a -> number(Integer.toString(string(a[0]).indexOf(string(a[1]))+1)));
        GlobalFunctions.add(entries,"ТРег","Title",1,1,a -> BslStrings.title(a[0]));
        GlobalFunctions.add(entries,"НСтр","NStr",1,2,a -> BslStrings.localized(a));
        GlobalFunctions.add(entries,"УниверсальноеВремя","UniversalTime",1,2,a -> BslDates.convertZone(a,true));
        GlobalFunctions.add(entries,"МестноеВремя","LocalTime",1,2,a -> BslDates.convertZone(a,false));
        GlobalFunctions.add(entries,"ЧасовойПояс","TimeZone",0,0,a -> java.time.ZoneId.systemDefault().getId());
        IoLibrary.register(entries);
        EnvironmentLibrary.register(entries);
        JsonLibrary.register(entries);
        HttpLibrary.register(entries);
        ProcessLibrary.register(entries);
        ProjectLibrary.register(entries);
        RegexLibrary.register(entries);
        HelperLibrary.register(entries);
        return Collections.unmodifiableMap(entries);
    }
    private static void add(Map<String, Entry> entries, String russian, String english, int arguments, Function<Object[], Object> body) {
        add(entries,russian,english,arguments,arguments,body);
    }
    static void add(Map<String, Entry> entries, String russian, String english, int min, int max, Function<Object[], Object> body) {
        Entry entry = new Entry(new Signature(russian, english, min, max), body);
        for (String name : new LinkedHashSet<>(List.of(russian.toLowerCase(Locale.ROOT), english.toLowerCase(Locale.ROOT))))
            if (entries.putIfAbsent(name, entry) != null) throw new IllegalStateException("Duplicate global function: " + name);
    }
    static void addOutput(Map<String,Entry> entries,String russian,String english,int min,int max,int output,Function<Object[],Object> body) {
        BitSet outputs=new BitSet();outputs.set(output);Entry entry=new Entry(new Signature(russian,english,min,max),body,outputs);
        for(String name:List.of(russian,english))if(entries.putIfAbsent(name.toLowerCase(Locale.ROOT),entry)!=null)throw new IllegalStateException("Duplicate global: "+name);
    }
    static Entry find(String normalizedName) { return FUNCTIONS.get(normalizedName); }
    static void alias(Map<String,Entry> entries,String existing,String alias) {
        Entry entry=entries.get(existing.toLowerCase(Locale.ROOT));
        if(entry==null || entries.putIfAbsent(alias.toLowerCase(Locale.ROOT),entry)!=null) throw new IllegalStateException("Invalid global alias: "+alias);
    }
    public static Map<String,Signature> names() {
        Map<String,Signature> names=new LinkedHashMap<>(); FUNCTIONS.forEach((name,entry) -> names.put(name,entry.signature)); return Collections.unmodifiableMap(names);
    }
    public static Object compilerSignatures() {
        BMap result = new BMap();
        Map<String,Signature> all = new LinkedHashMap<>(LegacyArity.signatures()); all.putAll(names());
        all.forEach((name,s) -> {
            BArray limits = new BArray(); limits.values.add(java.math.BigDecimal.valueOf(s.minArguments())); limits.values.add(java.math.BigDecimal.valueOf(s.maxArguments()));
            call(result,"Вставить",new Object[]{name,limits});
        });
        return result;
    }
    /** Registry metadata is reusable by documentation, tests and later compiler integration. */
    public static List<Signature> signatures() { return FUNCTIONS.values().stream().map(e -> e.signature).distinct().toList(); }
}
