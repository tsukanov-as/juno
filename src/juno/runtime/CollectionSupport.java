package juno.runtime;

import java.math.*;
import java.time.LocalDateTime;
import java.util.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Shared metadata, typed defaults and ordering for in-memory BSL collections. */
final class CollectionSupport {
    private CollectionSupport() {}
    static Object construct(String name, Object[] raw) {
        Object[] args = values(raw);
        return switch (name) {
            case "таблицазначений", "valuetable" -> { arguments(name, args, 0, 0); yield new Table(); }
            case "списокзначений", "valuelist" -> { arguments(name, args, 0, 0); yield new ValueList(); }
            case "деревозначений", "valuetree" -> { arguments(name, args, 0, 0); yield new ValueTree(); }
            case "сравнениезначений", "comparevalues" -> { arguments(name, args, 0, 0); yield new Comparison(); }
            default -> null;
        };
    }
    // Required value arguments may legitimately be Undefined (Find/Set/FillValues).
    static void arguments(String name, Object[] args, int min, int max) {
        if (args.length < min || args.length > max) throw error(name + ": ожидается " + min + (min == max ? "" : ".." + max) + " аргументов");
        for (int i = 0; i < min; i++) if (args[i] == Special.MISSING) throw error(name + ": пропущен аргумент " + (i + 1));
    }
    static int index(Object value) {
        value = read(value);
        if (!(value instanceof BigDecimal n)) throw error("Индекс/смещение коллекции требует Число");
        try { return n.setScale(0, RoundingMode.DOWN).intValueExact(); }
        catch (ArithmeticException e) { throw failure("Индекс вне диапазона int", e); }
    }
    static <T> T at(List<T> values, Object value) {
        int at = index(value);
        if (at < 0 || at >= values.size()) throw error("Индекс вне коллекции: " + at);
        return values.get(at);
    }
    static String text(Object value) {
        if (!(value instanceof String text)) throw error("Ожидается Строка");
        return text;
    }
    static boolean bool(Object value) {
        if (!(value instanceof Boolean flag)) throw error("Ожидается Булево");
        return flag;
    }
    static BigDecimal num(int number) { return BigDecimal.valueOf(number); }
    static Object initial(TypeDescription description) {
        if (description.types.contains(type("String"))) return description.qualify("");
        if (description.types.contains(type("Boolean"))) return false;
        if (description.types.contains(type("Date"))) return LocalDateTime.of(1, 1, 1, 0, 0);
        if (description.types.contains(type("Number"))) return BigDecimal.ZERO;
        if (description.types.contains(type("UUID"))) return HelperLibrary.ZERO_UUID;
        return UNDEFINED;
    }
    static Object adjust(TypeDescription description, Object raw) {
        Object value = read(raw);
        if (description.types.isEmpty())return value;
        if(description.types.contains(valueType(value)))return description.qualify(value);
        try {
            if (description.types.contains(type("String"))) return description.qualify(string(value));
            if (description.types.contains(type("Boolean"))) return ValueConversions.bool(value);
            if (description.types.contains(type("Date")) && value instanceof String text) return description.qualify(date(text));
            if (description.types.contains(type("Number"))) return description.qualify(decimal(value));
        } catch (RuntimeException ignored) { }
        return initial(description);
    }
    static Column column(Columns columns, Object value) {
        Column result = columns.find(value);
        if (result == null) throw error("Не найдена колонка: " + string(value));
        return result;
    }
    static List<Column> selected(Columns columns, Object specification) {
        if (omitted(specification) || text(specification).isBlank()) return new ArrayList<>(columns.values);
        List<Column> result = new ArrayList<>();
        for (String name : text(specification).split(",", -1)) {
            Column column = column(columns, name.strip());
            if (result.contains(column)) throw error("Повтор колонки: " + column.name);
            result.add(column);
        }
        return result;
    }
    static Object columnProperty(Column column, String name) {
        return switch (name) {
            case "имя", "name" -> column.name;
            case "типзначения", "valuetype" -> column.type;
            case "заголовок", "title" -> column.title;
            case "ширина", "width" -> num(column.width);
            default -> missingProperty(column.bslTypeName(), name);
        };
    }
    static void columnProperty(Column column, String name, Object value) {
        switch (name) {
            case "имя", "name" -> {
                String newName = text(value);
                identifier(newName);
                Column duplicate = column.columns.byName.get(newName.toLowerCase(Locale.ROOT));
                if (!newName.isEmpty() && duplicate != null && duplicate != column) throw error("Дублирующееся имя колонки: " + newName);
                if (column.columns.values.contains(column)) {
                    column.columns.byName.remove(column.normalizedName);
                    if (!newName.isEmpty()) column.columns.byName.put(newName.toLowerCase(Locale.ROOT), column);
                }
                column.name = newName;
                column.normalizedName = newName.toLowerCase(Locale.ROOT);
                changed(column.columns);
            }
            case "заголовок", "title" -> column.title = text(value);
            case "ширина", "width" -> { int width = index(value); if (width < 0) throw error("Отрицательная ширина колонки"); column.width = width; }
            default -> throw error("Свойство колонки доступно только для чтения: " + name);
        }
    }
    static void identifier(String name) {
        if (name.isEmpty()) return;
        if (!Character.isUnicodeIdentifierStart(name.codePointAt(0)) && name.charAt(0) != '_') throw error("Недопустимое имя колонки: " + name);
        if (!name.codePoints().allMatch(c -> Character.isUnicodeIdentifierPart(c) || c == '_')) throw error("Недопустимое имя колонки: " + name);
    }
    static Object columns(Columns columns, String name, Object[] args) {
        switch (name) {
            case "добавить", "add", "вставить", "insert": {
                boolean insert = name.equals("вставить") || name.equals("insert");
                arguments(name, args, insert ? 1 : 0, insert ? 5 : 4);
                int shift = insert ? 1 : 0;
                int at = insert ? index(args[0]) : columns.values.size();
                if (at < 0 || at > columns.values.size()) throw error("Индекс вставки колонки вне коллекции");
                String columnName = text(optional(args, shift, ""));
                identifier(columnName);
                if (!columnName.isEmpty() && columns.byName.containsKey(columnName.toLowerCase(Locale.ROOT))) throw error("Дублирующееся имя колонки: " + columnName);
                Object typeValue = optional(args, shift + 1, UNDEFINED);
                if (!omitted(typeValue) && !(typeValue instanceof TypeDescription)) throw error("Тип колонки требует ОписаниеТипов");
                Column column = new Column(columnName, typeValue instanceof TypeDescription td ? td : null, columns);
                column.title = text(optional(args, shift + 2, ""));
                column.width = index(optional(args, shift + 3, BigDecimal.ZERO));
                if (column.width < 0) throw error("Отрицательная ширина колонки");
                columns.values.add(at, column);
                if (!column.name.isEmpty()) columns.byName.put(column.normalizedName, column);
                ordinals(columns);
                changed(columns);
                return column;
            }
            case "получить", "get": arguments(name, args, 1, 1); return at(columns.values, args[0]);
            case "найти", "find": {
                arguments(name, args, 1, 1);
                Column column = columns.byName.get(text(args[0]).toLowerCase(Locale.ROOT));
                return column == null ? UNDEFINED : column;
            }
            case "количество", "count": arguments(name, args, 0, 0); return num(columns.values.size());
            case "индекс", "indexof": arguments(name, args, 1, 1); return num(columns.values.indexOf(args[0]));
            case "удалить", "delete": arguments(name, args, 1, 1); deleteColumn(columns, column(columns, args[0])); return UNDEFINED;
            case "очистить", "clear": {
                arguments(name, args, 0, 0);
                for (Column column : new ArrayList<>(columns.values)) deleteColumn(columns, column);
                return UNDEFINED;
            }
            case "сдвинуть", "move": {
                arguments(name, args, 2, 2);
                move(columns.values, column(columns, args[0]), index(args[1]));
                ordinals(columns); changed(columns); return UNDEFINED;
            }
            default: return missingMethod(columns.bslTypeName(), name);
        }
    }
    static void ordinals(Columns columns) {
        for (int i = 0; i < columns.values.size(); i++) columns.values.get(i).ordinal = i;
    }
    static void deleteColumn(Columns columns, Column column) {
        columns.values.remove(column);
        columns.byName.remove(column.normalizedName);
        if (columns.owner instanceof Table table) {
            for (Row row : table.rows) row.values.remove(column);
            for (TableLibrary.Index index : table.indexes.values) index.fields.remove(column);
        } else ((ValueTree) columns.owner).removeColumn(column);
        ordinals(columns);
        changed(columns);
    }
    static void changed(Columns columns) {
        if (columns.owner instanceof Table table) table.changed();
    }
    static void copyColumns(Columns source, Columns target, List<Column> selected) {
        for (Column column : selected) {
            Column copy = new Column(column.name, column.type, target);
            copy.title = column.title; copy.width = column.width;
            target.values.add(copy); if (!copy.name.isEmpty()) target.byName.put(copy.normalizedName, copy);
        }
    }
    static <T> void move(List<T> items, T item, int offset) {
        int from = items.indexOf(item);
        long target = (long) from + offset;
        if (from < 0 || target < 0 || target >= items.size()) throw error("Неправильное смещение внутри коллекции");
        items.remove(from); items.add((int) target, item);
    }
    static Object key(Object value) { return value instanceof BigDecimal n ? n.stripTrailingZeros() : value; }
    static int compare(Object first, Object second) {
        int a = rank(first), b = rank(second);
        if (a != b) return Integer.compare(a, b);
        if (first instanceof BigDecimal x && second instanceof BigDecimal y) return x.compareTo(y);
        if (first instanceof String x && second instanceof String y) return StringOrder.compareIgnoringCase(x, y);
        if (first instanceof Boolean x && second instanceof Boolean y) return x.compareTo(y);
        if (first instanceof LocalDateTime x && second instanceof LocalDateTime y) return x.compareTo(y);
        return string(first).compareTo(string(second));
    }
    private static int rank(Object value) {
        if (value == UNDEFINED || value == Special.MISSING) return 0;
        if (value == null || value == Special.NULL) return 1;
        if (value instanceof Boolean) return 2;
        if (value instanceof BigDecimal) return 3;
        if (value instanceof LocalDateTime) return 4;
        if (value instanceof String) return 5;
        return 6;
    }
    record SortKey(String name, boolean descending) {}
    static List<SortKey> sortKeys(Object value) {
        List<SortKey> keys = new ArrayList<>();
        for (String part : text(value).split(",", -1)) {
            String[] words = part.strip().split("\\s+");
            if (words[0].isEmpty() || words.length > 2) throw error("Неверная строка сортировки");
            String direction = words.length == 1 ? "asc" : words[1].toLowerCase(Locale.ROOT);
            if (!Set.of("asc", "desc", "возр", "убыв").contains(direction)) throw error("Неверное направление сортировки: " + direction);
            keys.add(new SortKey(words[0], direction.equals("desc") || direction.equals("убыв")));
        }
        return keys;
    }
    static void comparison(Object value) {
        if (!omitted(value) && !(value instanceof Comparison)) throw error("Сортировка требует СравнениеЗначений");
    }
    static final class Comparison implements NativeObject, TypedValue {
        public String bslTypeName() { return "СравнениеЗначений"; }
        public Object property(String name) { return missingProperty(bslTypeName(), name); }
        public Object invoke(String name, Object[] raw) {
            Object[] args = values(raw); arguments(name, args, 2, 2);
            if (!name.equals("сравнить") && !name.equals("compare")) return missingMethod(bslTypeName(), name);
            return num(Integer.signum(compare(args[0], args[1])));
        }
    }
    enum SortDirection implements TypedValue {
        ASCENDING, DESCENDING;
        static final Namespace VALUES = new Namespace();
        public String bslTypeName() { return "НаправлениеСортировки"; }
        public String toString() { return this == ASCENDING ? "Возр" : "Убыв"; }
        static final class Namespace implements NativeObject, TypedValue, Iterable<Object> {
            public String bslTypeName() { return "ПеречислениеНаправлениеСортировки"; }
            public String toString() { return bslTypeName(); }
            public Object property(String name) {
                return switch (name) {
                    case "возр", "asc" -> ASCENDING;
                    case "убыв", "desc" -> DESCENDING;
                    default -> missingProperty("НаправлениеСортировки", name);
                };
            }
            public Object invoke(String name, Object[] args) { return missingMethod("НаправлениеСортировки", name); }
            public Iterator<Object> iterator() { return List.<Object>of(ASCENDING, DESCENDING).iterator(); }
        }
    }
}
