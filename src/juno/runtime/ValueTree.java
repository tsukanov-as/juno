package juno.runtime;

import java.math.BigDecimal;
import java.util.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;
import static juno.runtime.CollectionSupport.*;

/** ValueTree keeps a single column schema and independent ordered sibling collections. */
final class ValueTree implements NativeObject, TypedValue {
    final Columns columns = new Columns(this);
    final Rows rows = new Rows(this, null);
    public String bslTypeName() { return "ДеревоЗначений"; }
    public String toString() { return bslTypeName(); }
    public Object property(String name) {
        return switch (name) {
            case "колонки", "columns" -> columns;
            case "строки", "rows" -> rows;
            default -> missingProperty(bslTypeName(), name);
        };
    }
    public Object invoke(String name, Object[] raw) {
        if (!name.equals("скопировать") && !name.equals("copy")) return missingMethod(bslTypeName(), name);
        arguments(name, values(raw), 0, 0);
        ValueTree out = new ValueTree(); copyColumns(columns, out.columns, columns.values);
        record Pair(Rows source, Rows target) {}
        Deque<Pair> pending = new ArrayDeque<>(); pending.add(new Pair(rows, out.rows));
        while (!pending.isEmpty()) {
            Execution.checkCancellation(); Pair pair = pending.removeFirst();
            for (TreeRow row : pair.source.items) {
                TreeRow copy = new TreeRow(pair.target); pair.target.items.add(copy);
                for (int i = 0; i < columns.values.size(); i++) copy.set(out.columns.values.get(i), row.get(columns.values.get(i)));
                pending.addLast(new Pair(row.rows, copy.rows));
            }
        }
        return out;
    }
    void removeColumn(Column column) {
        for (TreeRow row : rows.walk(true)) row.cells.remove(column);
    }
    static final class TreeRow implements NativeObject, TypedValue, IndexedObject, Iterable<Object> {
        final Rows container;
        final Rows rows;
        final IdentityHashMap<Column,Object> cells = new IdentityHashMap<>();
        TreeRow(Rows container) { this.container = container; rows = new Rows(container.owner, this); }
        ValueTree owner() { return container.owner; }
        Object get(Object selector) { return get(column(owner().columns, selector)); }
        Object get(Column column) {
            validate(column);
            return cells.containsKey(column) ? cells.get(column) : column.type.initial();
        }
        void set(Object selector, Object value) { set(column(owner().columns, selector), value); }
        void set(Column column, Object value) { validate(column); cells.put(column, adjust(column.type, value)); }
        void validate(Column column) {
            if (column.owner != owner() || !owner().columns.values.contains(column)) throw error("Колонка не принадлежит дереву или удалена");
        }
        public Iterator<Object> iterator() {
            List<Object> result = new ArrayList<>();
            for (Column column : owner().columns.values) result.add(get(column));
            return result.iterator();
        }
        public String bslTypeName() { return "СтрокаДереваЗначений"; }
        public String toString() { return bslTypeName(); }
        public Object property(String name) {
            return switch (name) {
                case "строки", "rows" -> rows;
                case "родитель", "parent" -> container.parent == null ? UNDEFINED : container.parent;
                default -> get(name);
            };
        }
        public void setProperty(String name, Object value) {
            if (Set.of("строки", "rows", "родитель", "parent").contains(name)) throw error("Свойство строки доступно только для чтения: " + name);
            set(name, value);
        }
        public Object getAt(Object index) { return get(index); }
        public void setAt(Object index, Object value) { set(index, value); }
        public Object invoke(String name, Object[] raw) {
            Object[] args = values(raw);
            return switch (name) {
                case "владелец", "owner" -> { arguments(name, args, 0, 0); yield owner(); }
                case "уровень", "level" -> {
                    arguments(name, args, 0, 0); int level = 0;
                    for (TreeRow parent = container.parent; parent != null; parent = parent.container.parent) level++;
                    yield num(level);
                }
                case "получить", "get" -> { arguments(name, args, 1, 1); yield get(BigDecimal.valueOf(index(args[0]))); }
                case "установить", "set" -> { arguments(name, args, 2, 2); set(BigDecimal.valueOf(index(args[0])), args[1]); yield UNDEFINED; }
                default -> missingMethod(bslTypeName(), name);
            };
        }
    }
    static final class Rows implements NativeObject, TypedValue, IndexedObject, Iterable<Object> {
        final ValueTree owner;
        final TreeRow parent;
        final List<TreeRow> items = new ArrayList<>();
        Rows(ValueTree owner, TreeRow parent) { this.owner = owner; this.parent = parent; }
        public String bslTypeName() { return "КоллекцияСтрокДереваЗначений"; }
        public String toString() { return bslTypeName(); }
        public Iterator<Object> iterator() { return new LiveIterator(items); }
        public Object getAt(Object index) { return at(items, index); }
        public Object property(String name) {
            if (name.equals("родитель") || name.equals("parent")) return parent == null ? UNDEFINED : parent;
            return missingProperty(bslTypeName(), name);
        }
        TreeRow row(Object selector) {
            if (selector instanceof TreeRow row) {
                if (row.container != this || !items.contains(row)) throw error("Строка не принадлежит коллекции строк дерева");
                return row;
            }
            return at(items, selector);
        }
        Iterable<TreeRow> walk(boolean recursive) {
            return () -> new Iterator<>() {
                final Deque<Iterator<TreeRow>> pending = new ArrayDeque<>();
                { pending.push(new ArrayList<>(items).iterator()); }
                public boolean hasNext() {
                    while (!pending.isEmpty() && !pending.peek().hasNext()) pending.pop();
                    return !pending.isEmpty();
                }
                public TreeRow next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    Execution.checkCancellation(); TreeRow row = pending.peek().next();
                    if (recursive && !row.rows.items.isEmpty()) pending.push(new ArrayList<>(row.rows.items).iterator());
                    return row;
                }
            };
        }
        public Object invoke(String name, Object[] raw) {
            Object[] args = values(raw);
            switch (name) {
                case "добавить", "add", "вставить", "insert": {
                    boolean insert = name.equals("вставить") || name.equals("insert");
                    arguments(name, args, insert ? 1 : 0, insert ? 1 : 0);
                    int at = insert ? index(args[0]) : items.size();
                    if (at < 0 || at > items.size()) throw error("Индекс вставки вне коллекции строк дерева");
                    TreeRow row = new TreeRow(this); items.add(at, row); return row;
                }
                case "получить", "get": arguments(name, args, 1, 1); return at(items, args[0]);
                case "количество", "count": arguments(name, args, 0, 0); return num(items.size());
                case "индекс", "indexof": arguments(name, args, 1, 1); return num(items.indexOf(args[0]));
                case "удалить", "delete": arguments(name, args, 1, 1); items.remove(row(args[0])); return UNDEFINED;
                case "очистить", "clear": arguments(name, args, 0, 0); items.clear(); return UNDEFINED;
                case "сдвинуть", "move": arguments(name, args, 2, 2); move(items, row(args[0]), index(args[1])); return UNDEFINED;
                case "найти", "find": {
                    arguments(name, args, 1, 3); List<Column> fields = selected(owner.columns, optional(args, 1, UNDEFINED));
                    for (TreeRow row : walk(bool(optional(args, 2, false)))) for (Column column : fields) if (eq(row.get(column), args[0])) return row;
                    return UNDEFINED;
                }
                case "найтистроки", "findrows": {
                    arguments(name, args, 1, 2); Map<Column,Object> criteria = TableLibrary.criteria(owner.columns, TableLibrary.filter(args[0]));
                    BArray out = new BArray();
                    for (TreeRow row : walk(bool(optional(args, 1, false)))) {
                        boolean match = true;
                        for (var entry : criteria.entrySet()) if (!eq(row.get(entry.getKey()), entry.getValue())) { match = false; break; }
                        if (match) out.values.add(row);
                    }
                    return out;
                }
                case "итог", "total": {
                    arguments(name, args, 1, 2); Column column = column(owner.columns, args[0]);
                    if (column.type.types.size() > 1 && !column.type.types.contains(type("Number"))) return UNDEFINED;
                    BigDecimal sum = BigDecimal.ZERO;
                    for (TreeRow row : walk(bool(optional(args, 1, false)))) sum = TableLibrary.addTotal(sum, row.get(column), column);
                    return sum;
                }
                case "выгрузитьколонку", "unloadcolumn": {
                    arguments(name, args, 1, 1); Column column = column(owner.columns, args[0]); BArray out = new BArray();
                    for (TreeRow row : items) out.values.add(row.get(column)); return out;
                }
                case "загрузитьколонку", "loadcolumn": {
                    arguments(name, args, 2, 2); BArray source = TableLibrary.array(args[0]); Column column = column(owner.columns, args[1]);
                    for (int i = 0; i < Math.min(items.size(), source.values.size()); i++) items.get(i).set(column, source.values.get(i));
                    return UNDEFINED;
                }
                case "сортировать", "sort": {
                    arguments(name, args, 1, 3); boolean recursive = bool(optional(args, 1, false)); comparison(optional(args, 2, UNDEFINED));
                    List<SortKey> keys = sortKeys(args[0]);
                    for (SortKey key : keys) column(owner.columns, key.name());
                    Comparator<TreeRow> comparator = (a, b) -> {
                        for (SortKey key : keys) {
                            Column column = column(owner.columns, key.name()); int result = compare(a.get(column), b.get(column));
                            if (result != 0) return key.descending() ? -result : result;
                        }
                        return 0;
                    };
                    items.sort(comparator);
                    if (recursive) for (TreeRow row : walk(true)) row.rows.items.sort(comparator);
                    return UNDEFINED;
                }
                default: return missingMethod(bslTypeName(), name);
            }
        }
    }
}
