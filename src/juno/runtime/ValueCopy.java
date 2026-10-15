package juno.runtime;

import java.math.*;
import java.time.LocalDateTime;
import java.util.*;
import static juno.runtime.RuntimeSupport.*;

/** Snapshot transport between jobs; preserves container aliases and cycles. */
public final class ValueCopy {
    private final IdentityHashMap<Object, Object> copies = new IdentityHashMap<>();
    private int depth;
    private ValueCopy() {}
    public static Object snapshot(Object value) { return new ValueCopy().copy(value); }
    private Object copy(Object value) {
        if (++depth > 256) { depth--; throw new BslException("Слишком глубокий граф параметров фонового задания"); }
        try { return copyValue(value); } finally { depth--; }
    }
    private Object copyValue(Object value) {
        if (value instanceof Ref) {
            Set<Object> refs = Collections.newSetFromMap(new IdentityHashMap<>());
            while (value instanceof Ref r) {
                if (!refs.add(value)) throw new BslException("Циклическая ссылка параметра");
                value = r.get();
            }
        }
        if (value == null || value instanceof Special || value instanceof String || value instanceof BigDecimal
                || value instanceof BigInteger || value instanceof Boolean || value instanceof LocalDateTime
                || value instanceof UUID || value instanceof Type || value instanceof TypeDescription || value instanceof ErrorInfo
                || value instanceof SearchDirection || value instanceof SearchDirection.Namespace || value instanceof RegexLibrary.Group
                || value instanceof CollectionSupport.SortDirection || value instanceof CollectionSupport.SortDirection.Namespace
                || value instanceof CollectionSupport.Comparison)
            return value;
        if (value instanceof TypeQualifiers.Qualifier)return value;
        if (value instanceof IoEnums.Value || value instanceof IoEnums.Namespace || value instanceof NativeFile || value instanceof JsonOptions) return value;
        if (value instanceof ApplicationContext.Resource || value instanceof BinaryBuffer)
            throw new BslException("Открытые ресурсы и изменяемые двоичные буферы нельзя передавать фоновому заданию");
        Object existing = copies.get(value); if (existing != null) return existing;
        if (value instanceof Binary b) { Binary out = new Binary(b.bytes.clone()); copies.put(value, out); return out; }
        if (value instanceof BArray a) {
            BArray out = new BArray(a.fixed); copies.put(value, out);
            for (Object item : a.values) out.values.add(copy(item)); return out;
        }
        if (value instanceof Struct s) {
            Struct out = new Struct(s.fixed); copies.put(value, out);
            out.originalNames.putAll(s.originalNames);
            for (var e : s.values.entrySet()) out.values.put(e.getKey(), new Entry(e.getValue().key, copy(e.getValue().value)));
            return out;
        }
        if (value instanceof BMap m) {
            BMap out = new BMap(m.fixed,m.caseInsensitive); copies.put(value, out);
            for (var e : m.values.entrySet()) out.values.put(copy(e.getKey()), new Entry(copy(e.getValue().key), copy(e.getValue().value)));
            return out;
        }
        if (value instanceof Table t) {
            Table out = new Table(); copies.put(t, out); copies.put(t.columns, out.columns); copies.put(t.indexes, out.indexes);
            copyColumns(t.columns, out.columns);
            for (TableLibrary.Index index : t.indexes.values) {
                TableLibrary.Index copied = new TableLibrary.Index(out.indexes, List.of());
                copies.put(index, copied); out.indexes.values.add(copied);
                for (Column field : index.fields) copied.fields.add((Column) copy(field));
            }
            for (Row r : t.rows) { Row nr = new Row(out); copies.put(r, nr); out.rows.add(nr); }
            for (int i = 0; i < t.rows.size(); i++) fillRow(t.rows.get(i), out.rows.get(i));
            return out;
        }
        if (value instanceof Row r) {
            Table owner = (Table) copy(r.owner);
            Row out = (Row) copies.get(r);
            if (out == null) { out = new Row(owner); copies.put(r, out); fillRow(r, out); }
            return out;
        }
        if (value instanceof Column c) {
            copy(c.owner);
            Column out = (Column) copies.get(c);
            if (out == null) {
                out = new Column(c.name, c.type, (Columns) copy(c.columns));
                out.title = c.title; out.width = c.width; out.ordinal = c.ordinal; copies.put(c, out);
            }
            return out;
        }
        if (value instanceof Columns c) { copy(c.owner); return copies.get(c); }
        if (value instanceof TableLibrary.Indexes indexes) { copy(indexes.owner); return copies.get(indexes); }
        if (value instanceof TableLibrary.Index index) {
            TableLibrary.Indexes owner = (TableLibrary.Indexes) copy(index.owner);
            TableLibrary.Index out = (TableLibrary.Index) copies.get(index);
            if (out == null) {
                out = new TableLibrary.Index(owner, List.of()); copies.put(index, out);
                for (Column field : index.fields) out.fields.add((Column) copy(field));
            }
            return out;
        }
        if (value instanceof ValueList list) {
            ValueList out = new ValueList(); copies.put(list, out);
            out.valueType = list.valueType; out.nextId = list.nextId;
            for (ValueList.Item item : list.items) {
                ValueList.Item copied = new ValueList.Item(out, item.id, UNDEFINED, item.presentation, item.check, UNDEFINED);
                copies.put(item, copied); out.items.add(copied);
            }
            out.availableValues = copy(list.availableValues);
            for (int i = 0; i < list.items.size(); i++) fillItem(list.items.get(i), out.items.get(i));
            return out;
        }
        if (value instanceof ValueList.Item item) {
            ValueList owner = (ValueList) copy(item.owner);
            ValueList.Item out = (ValueList.Item) copies.get(item);
            if (out == null) {
                out = new ValueList.Item(owner, item.id, UNDEFINED, item.presentation, item.check, UNDEFINED);
                copies.put(item, out); fillItem(item, out);
            }
            return out;
        }
        if (value instanceof ValueTree tree) {
            ValueTree out = new ValueTree(); copies.put(tree, out); copies.put(tree.columns, out.columns);
            copyColumns(tree.columns, out.columns); fillTreeRows(tree.rows, out.rows);
            return out;
        }
        if (value instanceof ValueTree.Rows rows) {
            ValueTree tree = (ValueTree) copy(rows.owner);
            ValueTree.Rows out = (ValueTree.Rows) copies.get(rows);
            if (out == null) {
                ValueTree.TreeRow parent = rows.parent == null ? null : (ValueTree.TreeRow) copy(rows.parent);
                if (copies.containsKey(rows)) return copies.get(rows);
                out = parent == null ? tree.rows : parent.rows;
                fillTreeRows(rows, out);
            }
            return out;
        }
        if (value instanceof ValueTree.TreeRow row) {
            ValueTree.Rows container = (ValueTree.Rows) copy(row.container);
            ValueTree.TreeRow out = (ValueTree.TreeRow) copies.get(row);
            if (out == null) {
                out = new ValueTree.TreeRow(container); copies.put(row, out);
                fillTreeRows(row.rows, out.rows); fillTreeRow(row, out);
            }
            return out;
        }
        throw new BslException("Неподдерживаемый параметр фонового задания: " + value.getClass().getSimpleName());
    }
    private void copyColumns(Columns source, Columns target) {
        for (Column column : source.values) {
            Column out = new Column(column.name, column.type, target);
            out.title = column.title; out.width = column.width;
            target.values.add(out);
            if (!out.name.isEmpty()) target.byName.put(out.normalizedName, out);
            copies.put(column, out);
        }
    }
    private void fillRow(Row source, Row target) {
        for (Column column : source.owner.columns.values) target.set((Column) copies.get(column), copy(source.get(column)));
    }
    private void fillItem(ValueList.Item source, ValueList.Item target) {
        target.value = copy(source.value); target.picture = copy(source.picture);
    }
    private void fillTreeRow(ValueTree.TreeRow source, ValueTree.TreeRow target) {
        for (Column column : source.owner().columns.values) target.set((Column) copies.get(column), copy(source.get(column)));
    }
    private void fillTreeRows(ValueTree.Rows source, ValueTree.Rows target) {
        record Pair(ValueTree.Rows source, ValueTree.Rows target) {}
        List<Pair> all = new ArrayList<>(); all.add(new Pair(source, target));
        for (int i = 0; i < all.size(); i++) {
            Pair pair = all.get(i); copies.put(pair.source, pair.target);
            for (ValueTree.TreeRow row : pair.source.items) {
                ValueTree.TreeRow out = new ValueTree.TreeRow(pair.target);
                pair.target.items.add(out); copies.put(row, out); copies.put(row.rows, out.rows);
                all.add(new Pair(row.rows, out.rows));
            }
        }
        for (Pair pair : all) for (int i = 0; i < pair.source.items.size(); i++) fillTreeRow(pair.source.items.get(i), pair.target.items.get(i));
    }
}
