package juno.runtime;

import java.math.BigDecimal;
import java.util.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;
import static juno.runtime.CollectionSupport.*;

/** ValueTable operations and real, lazily rebuilt composite indexes. */
final class TableLibrary {
    private TableLibrary() {}
    static final class Index implements NativeObject, TypedValue, IndexedObject, Iterable<Object> {
        final Indexes owner;
        final List<Column> fields;
        Map<List<Object>,List<Row>> buckets = Map.of();
        long builtVersion = -1, builds, lookups;
        Index(Indexes owner, List<Column> fields) { this.owner = owner; this.fields = new ArrayList<>(fields); }
        public String bslTypeName() { return "ИндексКоллекции"; }
        public String toString() { return bslTypeName(); }
        public Object property(String name) { return missingProperty(bslTypeName(), name); }
        public Object invoke(String name, Object[] args) { return missingMethod(bslTypeName(), name); }
        public Object getAt(Object value) { return at(fields, value).name; }
        public Iterator<Object> iterator() { return fields.stream().map(c -> (Object) c.name).toList().iterator(); }
        List<Object> rowKey(Row row) {
            List<Object> tuple = new ArrayList<>(fields.size());
            for (Column field : fields) tuple.add(key(row.get(field)));
            return tuple;
        }
        void invalidate() { buckets = Map.of(); builtVersion = -1; }
        List<Row> lookup(Struct filter) {
            Table table = owner.owner;
            if (builtVersion != table.version) {
                buckets = new HashMap<>();
                for (Row row : table.rows) { Execution.checkCancellation(); buckets.computeIfAbsent(rowKey(row), k -> new ArrayList<>()).add(row); }
                builtVersion = table.version; builds++;
            }
            List<Object> tuple = new ArrayList<>(fields.size());
            for (Column column : fields) tuple.add(key(filter.values.get(column.normalizedName).value));
            lookups++;
            return buckets.getOrDefault(tuple, List.of());
        }
    }
    static final class Indexes implements NativeObject, TypedValue, IndexedObject, Iterable<Object> {
        final Table owner;
        final List<Index> values = new ArrayList<>();
        long examinedRows;
        Indexes(Table owner) { this.owner = owner; }
        public String bslTypeName() { return "ИндексыКоллекции"; }
        public String toString() { return bslTypeName(); }
        public Object property(String name) { return missingProperty(bslTypeName(), name); }
        public Object getAt(Object value) { return at(values, value); }
        public Iterator<Object> iterator() { return new ArrayList<Object>(values).iterator(); }
        void clear() { for (Index index : values) index.invalidate(); values.clear(); }
        public Object invoke(String name, Object[] raw) {
            Object[] args = IoSupport.values(raw);
            switch (name) {
                case "добавить", "add": {
                    arguments(name, args, 1, 1);
                    String specification = text(args[0]);
                    List<Column> fields = new ArrayList<>();
                    if (!specification.isBlank()) for (String part : specification.split(",", -1)) fields.add(column(owner.columns, part.strip()));
                    Index result = new Index(this, fields); values.add(result); return result;
                }
                case "количество", "count": arguments(name, args, 0, 0); return num(values.size());
                case "очистить", "clear": arguments(name, args, 0, 0); clear(); return UNDEFINED;
                case "удалить", "delete": {
                    arguments(name, args, 1, 1);
                    int at = args[0] instanceof Index item ? values.indexOf(item) : index(args[0]);
                    if (at < 0 || at >= values.size()) throw error("Не найден индекс коллекции");
                    values.remove(at).invalidate(); return UNDEFINED;
                }
                default: return missingMethod(bslTypeName(), name);
            }
        }
    }
    static Row row(Table table, Object selection) {
        if (selection instanceof Row row) {
            if (row.owner != table || !table.rows.contains(row)) throw error("Строка не принадлежит таблице");
            return row;
        }
        return at(table.rows, selection);
    }
    static Object invoke(Table table, String name, Object[] args) {
        switch (name) {
            case "добавить", "add", "вставить", "insert": {
                boolean insert = name.equals("вставить") || name.equals("insert");
                arguments(name, args, insert ? 1 : 0, insert ? 1 : 0);
                int at = insert ? index(args[0]) : table.rows.size();
                if (at < 0 || at > table.rows.size()) throw error("Индекс вставки строки вне таблицы");
                Row result = new Row(table); table.rows.add(at, result); table.changed(); return result;
            }
            case "получить", "get": arguments(name, args, 1, 1); return at(table.rows, args[0]);
            case "количество", "count": arguments(name, args, 0, 0); return num(table.rows.size());
            case "индекс", "indexof": arguments(name, args, 1, 1); return num(table.rows.indexOf(args[0]));
            case "удалить", "delete": arguments(name, args, 1, 1); table.rows.remove(row(table, args[0])); table.changed(); return UNDEFINED;
            case "очистить", "clear": arguments(name, args, 0, 0); table.rows.clear(); table.changed(); return UNDEFINED;
            case "сдвинуть", "move": arguments(name, args, 2, 2); move(table.rows, row(table, args[0]), index(args[1])); table.changed(); return UNDEFINED;
            case "найти", "find": {
                arguments(name, args, 1, 2);
                List<Column> fields = selected(table.columns, optional(args, 1, UNDEFINED));
                for (Row row : table.rows) for (Column column : fields) if (eq(row.get(column), args[0])) return row;
                return UNDEFINED;
            }
            case "найтистроки", "findrows": arguments(name, args, 1, 1); return findRows(table, filter(args[0]));
            case "итог", "total": arguments(name, args, 1, 1); return total(table.rows, column(table.columns, text(args[0])));
            case "заполнитьзначения", "fillvalues": {
                arguments(name, args, 1, 2);
                List<Column> fields = selected(table.columns, optional(args, 1, UNDEFINED));
                for (Row row : table.rows) for (Column column : fields) row.set(column, args[0]);
                return UNDEFINED;
            }
            case "выгрузитьколонку", "unloadcolumn": {
                arguments(name, args, 1, 1); Column column = column(table.columns, args[0]);
                BArray out = new BArray(); for (Row row : table.rows) out.values.add(row.get(column)); return out;
            }
            case "загрузитьколонку", "loadcolumn": {
                arguments(name, args, 2, 2); BArray source = array(args[0]); Column column = column(table.columns, args[1]);
                for (int i = 0; i < Math.min(source.values.size(), table.rows.size()); i++) table.rows.get(i).set(column, source.values.get(i));
                return UNDEFINED;
            }
            case "скопировать", "copy": arguments(name, args, 0, 2); return copy(table, optional(args, 0, UNDEFINED), optional(args, 1, UNDEFINED), false);
            case "скопироватьколонки", "copycolumns": arguments(name, args, 0, 1); return copy(table, UNDEFINED, optional(args, 0, UNDEFINED), true);
            case "свернуть", "groupby": arguments(name, args, 1, 2); group(table, text(args[0]), text(optional(args, 1, ""))); return UNDEFINED;
            case "сортировать", "sort": {
                arguments(name, args, 1, 2); comparison(optional(args, 1, UNDEFINED));
                sort(table.rows, table.columns, args[0]); table.changed(); return UNDEFINED;
            }
            default: return missingMethod(table.bslTypeName(), name);
        }
    }
    static Struct filter(Object value) {
        if (!(value instanceof Struct filter) || filter.fixed) throw error("Отбор требует Структура");
        return filter;
    }
    static BArray array(Object value) {
        if (!(value instanceof BArray array) || array.fixed) throw error("Ожидается Массив");
        return array;
    }
    static Map<Column,Object> criteria(Columns columns, Struct filter) {
        Map<Column,Object> result = new LinkedHashMap<>();
        for (Entry entry : filter.values.values()) result.put(column(columns, entry.key), entry.value);
        return result;
    }
    static boolean matches(Row row, Map<Column,Object> criteria) {
        for (var entry : criteria.entrySet()) if (!eq(row.get(entry.getKey()), entry.getValue())) return false;
        return true;
    }
    static BArray findRows(Table table, Struct filter) {
        Map<Column,Object> fields = criteria(table.columns, filter);
        Index best = null;
        for (Index index : table.indexes.values) if (!index.fields.isEmpty() && fields.keySet().containsAll(index.fields)
            && (best == null || index.fields.size() > best.fields.size())) best = index;
        List<Row> candidates = best == null ? table.rows : best.lookup(filter);
        BArray result = new BArray(); table.indexes.examinedRows = 0;
        for (Row row : candidates) {
            Execution.checkCancellation(); table.indexes.examinedRows++;
            if (matches(row, fields)) result.values.add(row);
        }
        return result;
    }
    static Table copy(Table source, Object rows, Object fieldSpec, boolean columnsOnly) {
        List<Column> fields = selected(source.columns, fieldSpec);
        Table out = new Table(); copyColumns(source.columns, out.columns, fields);
        for (Index index : source.indexes.values) if (fields.containsAll(index.fields)) {
            List<Column> copied = new ArrayList<>();
            for (Column field : index.fields) copied.add(out.columns.values.get(fields.indexOf(field)));
            out.indexes.values.add(new Index(out.indexes, copied));
        }
        if (columnsOnly) return out;
        List<?> chosen = omitted(rows) ? source.rows : rows instanceof Struct filter ? findRows(source, filter).values : array(rows).values;
        for (Object value : chosen) {
            if (!(value instanceof Row row)) throw error("Скопировать ожидает массив строк таблицы");
            Row copied = new Row(out); out.rows.add(copied);
            for (int i = 0; i < fields.size(); i++) {
                Column field = fields.get(i);
                Object cell = row.owner == source ? row.get(field) : row.get(field.name);
                copied.set(out.columns.values.get(i), cell);
            }
        }
        return out;
    }
    static Object total(List<Row> rows, Column column) {
        if (column.type.types.size() > 1 && !column.type.types.contains(type("Number"))) return UNDEFINED;
        BigDecimal result = BigDecimal.ZERO;
        for (Row row : rows) result = addTotal(result, row.get(column), column);
        return result;
    }
    static BigDecimal addTotal(BigDecimal sum, Object value, Column column) {
        if (value instanceof BigDecimal number) return sum.add(number);
        if (column.type.types.size() == 1 && !column.type.types.contains(type("Undefined"))) return sum.add(decimal(value));
        return sum;
    }
    static void group(Table table, String groupSpec, String sumSpec) {
        List<Column> groups = groupSpec.isBlank() ? List.of() : selected(table.columns, groupSpec);
        List<Column> sums = sumSpec.isBlank() ? List.of() : selected(table.columns, sumSpec);
        for (Column column : groups) if (sums.contains(column)) throw error("Колонки группировки и суммирования пересекаются");
        record Aggregate(Row first, BigDecimal[] sums) {}
        Map<List<Object>,Aggregate> aggregate = new LinkedHashMap<>();
        for (Row row : table.rows) {
            List<Object> key = new ArrayList<>(); for (Column column : groups) key.add(key(row.get(column)));
            Aggregate item = aggregate.computeIfAbsent(key, k -> new Aggregate(row, new BigDecimal[sums.size()]));
            for (int i = 0; i < sums.size(); i++) item.sums[i] = addTotal(item.sums[i] == null ? BigDecimal.ZERO : item.sums[i], row.get(sums.get(i)), sums.get(i));
        }
        List<Row> rows = new ArrayList<>();
        for (Aggregate item : aggregate.values()) {
            for (int i = 0; i < sums.size(); i++) item.first.set(sums.get(i), item.sums[i]);
            rows.add(item.first);
        }
        table.rows.clear(); table.rows.addAll(rows);
        table.indexes.clear();
        for (Column column : new ArrayList<>(table.columns.values)) if (!groups.contains(column) && !sums.contains(column)) deleteColumn(table.columns, column);
        table.changed();
    }
    static void sort(List<Row> rows, Columns columns, Object specification) {
        List<SortKey> keys = sortKeys(specification);
        for (SortKey key : keys) column(columns, key.name());
        rows.sort((a, b) -> {
            for (SortKey key : keys) {
                Column column = column(columns, key.name());
                int value = compare(a.get(column), b.get(column));
                if (value != 0) return key.descending() ? -value : value;
            }
            return 0;
        });
    }
}
