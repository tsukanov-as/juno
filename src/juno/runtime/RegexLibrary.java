package juno.runtime;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Platform-shaped regex globals over a bounded, explicitly documented JDK 21 dialect. */
final class RegexLibrary {
    static final int MAX_PATTERN = 8192, MAX_INPUT = 16 * 1024 * 1024,
        MAX_RESULTS = 100_000, MAX_CAPTURE_CHARS = 16 * 1024 * 1024, MAX_GROUPS = 256, CACHE_SIZE = 64;
    private record Key(String pattern, int flags) {}
    private static final Map<Key,Pattern> CACHE = new LinkedHashMap<>(CACHE_SIZE, .75f, true);

    static void register(Map<String,GlobalFunctions.Entry> entries) {
        GlobalFunctions.add(entries, "СтрНайтиПоРегулярномуВыражению", "StrFindByRegularExpression", 2, 7, RegexLibrary::find);
        GlobalFunctions.add(entries, "СтрНайтиВсеПоРегулярномуВыражению", "StrFindAllByRegularExpression", 2, 4, RegexLibrary::findAll);
        GlobalFunctions.add(entries, "СтрЗаменитьПоРегулярномуВыражению", "StrReplaceByRegularExpression", 3, 5, RegexLibrary::replace);
        GlobalFunctions.add(entries, "СтрПодобнаПоРегулярномуВыражению", "StrLikeByRegularExpression", 2, 4, RegexLibrary::like);
    }

    private static String text(Object value, String name, int limit) {
        if (!(value instanceof String text)) throw error(name + ": ожидается Строка");
        if (text.length() > limit) throw error(name + ": превышен лимит " + limit + " UTF-16 единиц");
        return text;
    }
    private static boolean flag(Object[] args, int index) {
        Object value = optional(args, index, false);
        if (!(value instanceof Boolean flag)) throw error("Регулярное выражение: флаг должен иметь тип Булево");
        return flag;
    }
    private static Pattern pattern(Object value, boolean ignoreCase, boolean multiline) {
        String source = text(value, "РегулярноеВыражение", MAX_PATTERN);
        int flags = Pattern.UNICODE_CHARACTER_CLASS | (ignoreCase ? Pattern.CASE_INSENSITIVE : 0)
            | (multiline ? Pattern.MULTILINE : 0);
        Key key = new Key(source, flags);
        synchronized (CACHE) {
            Pattern cached = CACHE.get(key);
            if (cached != null) return cached;
        }
        Pattern compiled;
        try {
            compiled = Pattern.compile(source, flags);
            if (!compiled.namedGroups().isEmpty())
                throw error("Именованные группы регулярных выражений не поддерживаются в целевой 1С 8.3.27");
            if (compiled.matcher("").groupCount() > MAX_GROUPS)
                throw error("Регулярное выражение: превышен лимит " + MAX_GROUPS + " групп");
        } catch (PatternSyntaxException cause) {
            BslException failure = error("Неверное регулярное выражение: позиция " + (cause.getIndex() + 1)
                + " (UTF-16): " + cause.getDescription());
            failure.initCause(cause);
            throw failure;
        } catch (StackOverflowError cause) { throw recursion(cause); }
        synchronized (CACHE) {
            CACHE.put(key, compiled);
            if (CACHE.size() > CACHE_SIZE) CACHE.remove(CACHE.keySet().iterator().next());
        }
        return compiled;
    }
    private static BslException recursion(StackOverflowError cause) {
        BslException failure = error("Регулярное выражение: слишком глубокий шаблон или поиск");
        failure.initCause(cause);
        return failure;
    }

    /** A deterministic work ceiling, not a timer or an interruptible worker thread. */
    private static final class Budget {
        long maximumSteps;
        final int inputLength;
        long steps;
        int objects, captureChars;
        Budget(int inputLength) {
            this.inputLength = inputLength;
            maximumSteps = Math.max(10_000_000L, 64L * inputLength);
        }
        void replacement(int length) { maximumSteps = Math.max(maximumSteps, 64L * (inputLength + length)); }
        void step() {
            if (++steps > maximumSteps) throw error("Регулярное выражение: превышен лимит работы (" + maximumSteps + " условных шагов)");
            if ((steps & 1023) == 0) Execution.checkCancellation();
        }
        void capture(int length) {
            if (++objects > MAX_RESULTS || length > MAX_CAPTURE_CHARS - captureChars)
                throw error("Регулярное выражение: превышен лимит результатов или текста групп");
            captureChars += length;
        }
    }
    private static final class CheckedText implements CharSequence {
        final String text;
        final int offset, length;
        final Budget budget;
        CheckedText(String text, Budget budget) { this(text, 0, text.length(), budget); }
        CheckedText(String text, int offset, int length, Budget budget) {
            this.text = text; this.offset = offset; this.length = length; this.budget = budget;
        }
        public int length() { return length; }
        public char charAt(int index) {
            if (index < 0 || index >= length) throw new IndexOutOfBoundsException(index);
            budget.step();
            return text.charAt(offset + index);
        }
        public CharSequence subSequence(int start, int end) {
            if (start < 0 || end > length || start > end) throw new IndexOutOfBoundsException();
            return new CheckedText(text, offset + start, end - start, budget);
        }
        public String toString() { return text.substring(offset, offset + length); }
    }
    private static final class Search {
        final String source;
        final Matcher matcher;
        final Budget budget;
        int firstStart;
        boolean found;
        Search(Object[] args, int flagIndex) {
            source = text(args[0], "Строка", MAX_INPUT);
            Pattern pattern = pattern(args[1], flag(args, flagIndex), flag(args, flagIndex + 1));
            budget = new Budget(source.length());
            matcher = pattern.matcher(new CheckedText(source, budget));
            Execution.checkCancellation();
        }
        boolean next() {
            Execution.checkCancellation();
            try {
                // JDK advances an empty match by one UTF-16 unit. ICU advances a code point.
                int start = !found ? firstStart : matcher.start() == matcher.end() && matcher.end() < source.length()
                    && Character.isHighSurrogate(source.charAt(matcher.end()))
                    && matcher.end() + 1 < source.length() && Character.isLowSurrogate(source.charAt(matcher.end() + 1))
                        ? matcher.end() + 2 : -1;
                found = start >= 0 ? matcher.find(start) : matcher.find();
                return found;
            } catch (StackOverflowError cause) { throw recursion(cause); }
        }
        boolean at(int index) {
            Execution.checkCancellation();
            budget.step();
            try {
                return matcher.region(index, source.length()).useAnchoringBounds(false).useTransparentBounds(true).lookingAt();
            } catch (StackOverflowError cause) { throw recursion(cause); }
        }
        Result result() {
            Group overall = group(0);
            List<Group> groups = new ArrayList<>();
            for (int i = 1; i <= matcher.groupCount(); i++) groups.add(group(i));
            return new Result(overall.start, overall.length, overall.value, groups);
        }
        private Group group(int index) {
            int begin = matcher.start(index), end = matcher.end(index);
            int length = begin < 0 ? 0 : end - begin;
            budget.capture(length);
            return new Group(begin < 0 ? 0 : begin + 1, length, begin < 0 ? "" : source.substring(begin, end));
        }
    }

    static class Group implements NativeObject, TypedValue {
        final int start, length;
        final String value;
        Group(int start, int length, String value) { this.start = start; this.length = length; this.value = value; }
        public String bslTypeName() { return "ГруппаРезультатаПоискаПоРегулярномуВыражению"; }
        public String toString() { return bslTypeName(); }
        public Object property(String name) {
            return switch (name) {
                case "начальнаяпозиция", "startindex" -> BigDecimal.valueOf(start);
                case "длина", "length" -> BigDecimal.valueOf(length);
                case "значение", "value" -> value;
                default -> missingProperty(bslTypeName(), name);
            };
        }
        public Object invoke(String name, Object[] args) { return missingMethod(bslTypeName(), name); }
    }
    static final class Result extends Group {
        private final List<Group> groups;
        Result(int start, int length, String value, List<Group> groups) {
            super(start, length, value);
            this.groups = List.copyOf(groups);
        }
        public String bslTypeName() { return "РезультатПоискаПоРегулярномуВыражению"; }
        public Object invoke(String name, Object[] args) {
            if (name.equals("получитьгруппы") || name.equals("getgroups")) {
                arity(name, args, 0, 0);
                BArray array = new BArray();
                array.values.addAll(groups);
                return array;
            }
            return missingMethod(bslTypeName(), name);
        }
    }
    private static Result missing() { return new Result(0, 0, "", List.of()); }

    private static Object findAll(Object[] args) {
        String source = text(args[0], "Строка", MAX_INPUT);
        if (source.isEmpty()) return new BArray();
        if (text(args[1], "РегулярноеВыражение", MAX_PATTERN).isEmpty()) return new BArray();
        Search search = new Search(args, 2);
        BArray results = new BArray();
        while (search.next()) {
            if (results.values.size() == MAX_RESULTS) throw error("Регулярное выражение: слишком много совпадений");
            results.values.add(search.result());
        }
        return results;
    }
    private static Object like(Object[] args) {
        Search search = new Search(args, 2);
        try { return search.matcher.matches(); }
        catch (StackOverflowError cause) { throw recursion(cause); }
    }
    private static int searchNumber(Object value) {
        if (!(value instanceof BigDecimal number)) throw error("Регулярное выражение: ожидается Число");
        try { return number.setScale(0, java.math.RoundingMode.DOWN).intValueExact(); }
        catch (ArithmeticException cause) { throw failure("Регулярное выражение: число вне диапазона int", cause); }
    }
    private static Object find(Object[] args) {
        String source = text(args[0], "Строка", MAX_INPUT);
        if (source.isEmpty()) return missing();
        if (text(args[1], "РегулярноеВыражение", MAX_PATTERN).isEmpty()) return missing();
        Object direction = optional(args, 2, SearchDirection.FROM_BEGINNING);
        if (!(direction instanceof SearchDirection)) throw error("Регулярное выражение: неверное направление поиска");
        boolean reverse = direction == SearchDirection.FROM_END;
        int start = searchNumber(optional(args, 3, BigDecimal.valueOf(reverse ? source.length() : 1)));
        int occurrence = searchNumber(optional(args, 4, BigDecimal.ONE));
        if (occurrence < 1) throw error("Регулярное выражение: номер вхождения должен быть положительным");
        if (start < 1 || start > source.length()) throw error("Регулярное выражение: неверная начальная позиция");
        Search search = new Search(args, 5);
        if (reverse) {
            int end = source.length();
            for (int index = start - 1; index >= 0; index--) {
                if (search.at(index) && search.matcher.end() <= end) {
                    if (--occurrence == 0) return search.result();
                    end = search.matcher.start();
                }
            }
        } else {
            search.firstStart = start - 1;
            while (search.next()) if (--occurrence == 0) return search.result();
        }
        return missing();
    }
    private static Object replace(Object[] args) {
        Search search = new Search(args, 3);
        String replacement = text(args[2], "ПодстрокаЗамены", MAX_CAPTURE_CHARS);
        search.budget.replacement(replacement.length());
        expand(replacement, search.matcher, search.budget, true);
        StringBuilder result = new StringBuilder();
        int end = 0, count = 0;
        while (search.next()) {
            if (++count > MAX_RESULTS) throw error("Регулярное выражение: слишком много совпадений");
            append(result, search.source.substring(end, search.matcher.start()));
            append(result, expand(replacement, search.matcher, search.budget, false));
            end = search.matcher.end();
        }
        append(result, search.source.substring(end));
        return result.toString();
    }
    private static void append(StringBuilder target, String text) {
        if (text.length() > MAX_CAPTURE_CHARS - target.length()) throw error("Регулярное выражение: превышен лимит текста замены");
        target.append(text);
    }
    private static String expand(String replacement, Matcher matcher, Budget budget, boolean validateOnly) {
        StringBuilder result = new StringBuilder();
        try {
            for (int i = 0; i < replacement.length(); i++) {
                budget.step();
                char c = replacement.charAt(i);
                if (c == '\\') {
                    if (++i == replacement.length()) throw new IllegalArgumentException("trailing escape");
                    if (!validateOnly) append(result, String.valueOf(replacement.charAt(i)));
                } else if (c == '$' && i + 1 < replacement.length()
                    && replacement.charAt(i + 1) >= '0' && replacement.charAt(i + 1) <= '9') {
                    int group = replacement.charAt(++i) - '0';
                    if (group > matcher.groupCount()) throw new IllegalArgumentException("unknown group " + group);
                    while (group != 0 && i + 1 < replacement.length()) {
                        char next = replacement.charAt(i + 1);
                        if (next < '0' || next > '9') break;
                        int candidate = group * 10 + next - '0';
                        if (candidate > matcher.groupCount()) break;
                        group = candidate;
                        i++;
                        budget.step();
                    }
                    if (!validateOnly) {
                        String captured = matcher.group(group);
                        if (captured != null) append(result, captured);
                    }
                } else {
                    int begin = i;
                    while (i + 1 < replacement.length() && replacement.charAt(i + 1) != '$'
                        && replacement.charAt(i + 1) != '\\') { i++; budget.step(); }
                    if (!validateOnly) append(result, replacement.substring(begin, i + 1));
                }
            }
            return result.toString();
        } catch (IllegalArgumentException | IndexOutOfBoundsException cause) {
            throw failure("Неверная подстрока замены регулярного выражения", cause);
        }
    }
}
