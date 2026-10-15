import java.io.*;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import juno.Main;
import juno.runtime.*;
import static juno.runtime.RuntimeSupport.*;

/** Index use/invalidation, graph snapshots and old compiler entry points. */
public final class CollectionLibraryTests {
    static BigDecimal n(int value) { return BigDecimal.valueOf(value); }
    static Object make(String name, Object... args) { return construct(name, args); }
    static Object p(Object object, String name) { return read(property(object, name)); }
    static void put(Object object, String name, Object value) { assign(property(object, name), value); }
    static Object c(Object object, String name, Object... args) { return call(object, name, args); }
    static Object at(Object object, int at) { return getIndex(object, n(at)); }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void fails(Runnable body) {
        try { body.run(); } catch (BslException expected) { return; }
        throw new AssertionError("Expected a BSL error");
    }
    static Object roots(Object... objects) {
        Object out = make("Array"); for (Object object : objects) c(out, "Add", object); return out;
    }
    static long counter(Object object, String field) throws Exception {
        Field value = object.getClass().getDeclaredField(field); value.setAccessible(true); return value.getLong(object);
    }
    static boolean emptyBuckets(Object index) throws Exception {
        Field field = index.getClass().getDeclaredField("buckets"); field.setAccessible(true);
        return ((Map<?,?>) field.get(index)).isEmpty();
    }
    static Object filter(String fields, Object... values) {
        Object[] args = new Object[values.length + 1]; args[0] = fields;
        System.arraycopy(values, 0, args, 1, values.length); return make("Structure", args);
    }
    static void indexes() throws Exception {
        Object table = make("ValueTable"), columns = p(table, "Columns");
        Object key = c(columns, "Add", "Key"), category = c(columns, "Add", "Category");
        for (int i = 0; i < 5000; i++) {
            Object row = c(table, "Add"); put(row, "Key", n(i)); put(row, "Category", n(i % 10));
        }
        Object indexes = p(table, "Indexes"), composite = c(indexes, "Add", "Key,Category");
        Object wanted = filter("Category,Key", n(3), new BigDecimal("123.00"));
        Object rows = c(table, "FindRows", wanted);
        check(c(rows, "Count").equals(n(1)) && at(rows, 0) == at(table, 123), "composite index ignores filter order and decimal scale");
        check(counter(indexes, "examinedRows") == 1 && counter(composite, "builds") == 1, "lookup uses index buckets rather than scanning rows");
        c(table, "FindRows", wanted);
        check(counter(composite, "builds") == 1 && counter(composite, "lookups") == 2, "unchanged table reuses index");
        put(at(table, 123), "Key", n(9999));
        check(emptyBuckets(composite), "mutation releases cached row/key graph before another query");
        c(table, "FindRows", wanted);
        check(counter(composite, "builds") == 2 && counter(indexes, "examinedRows") == 0, "cell mutation invalidates index");
        Object replacement = c(table, "Add"); put(replacement, "Key", n(123)); put(replacement, "Category", n(3));
        check(at(c(table, "FindRows", wanted), 0) == replacement, "addition visible to index");
        c(table, "Delete", replacement);
        check(c(c(table, "FindRows", wanted), "Count").equals(n(0)), "deletion visible to index");
        c(columns, "Move", category, n(-1)); put(category, "Name", "Kind");
        check(at(composite, 1).equals("Kind") && at(at(table, 3), 0).equals(n(3)), "column move/rename retains cells and index definition");
        put(at(table, 123), "Key", n(123));
        check(c(c(table, "FindRows", filter("Key,Kind", n(123), n(3))), "Count").equals(n(1)), "renamed index works");
        c(columns, "Delete", key);
        check(at(composite, 0).equals("Kind"), "deleted column removed from index fields");
        c(columns, "Delete", category);
        check(c(indexes, "Count").equals(n(1)) && !hasNext(iterator(composite)), "empty index remains metadata");
        c(table, "Clear");
        check(emptyBuckets(composite), "clear releases index buckets immediately");
        check(c(c(table, "FindRows", filter("")), "Count").equals(n(0)), "clear invalidates indexed table");

        Object other = make("ValueTable"); c(p(other, "Columns"), "Add", "Key"); put(c(other, "Add"), "Key", n(1));
        Object otherIndexes = p(other, "Indexes"), removed = c(otherIndexes, "Add", "Key");
        c(other, "FindRows", filter("Key", n(1))); c(otherIndexes, "Delete", removed);
        check(emptyBuckets(removed), "deleted index releases cache while its object is still referenced");
        Object cleared = c(otherIndexes, "Add", "Key"); c(other, "FindRows", filter("Key", n(1)));
        c(otherIndexes, "Clear"); check(emptyBuckets(cleared), "cleared index releases cache");
        Object tableCleared = c(otherIndexes, "Add", "Key"); c(other, "FindRows", filter("Key", n(1)));
        check(!emptyBuckets(tableCleared), "clear fixture has built buckets");
        c(other, "Clear"); check(emptyBuckets(tableCleared), "Table.Clear releases cached removed rows immediately");
    }
    static void schemasAndTypedValues() {
        Object table = make("ValueTable"), columns = p(table, "Columns");
        Object number = make("TypeDescription", "Number"), text = make("TypeDescription", "String");
        Object a = c(columns, "Add"), b = c(columns, "Add");
        check(p(a, "Name").equals("") && p(b, "Name").equals("") && p(a, "Title").equals(""), "duplicate unnamed columns");
        Object row = c(table, "Add"); assign(index(row, n(0)), n(7)); assign(index(row, n(1)), n(9));
        put(a, "Name", "Code"); put(b, "Name", "Label");
        check(p(row, "Code").equals(n(7)) && p(a, "Title").equals(""), "1C default empty title and identity retains cells");
        put(a, "Title", ""); put(a, "Name", "Changed");
        check(p(a, "Title").equals(""), "explicit empty title does not follow name");
        Object count = c(columns, "Insert", n(0), "Count", number, "Items", n(12));
        check(p(row, "Count").equals(n(0)) && p(count, "ValueType") == number, "typed column added after rows gets default");
        put(row, "Count", "42"); check(p(row, "Count").equals(n(42)), "numeric coercion");
        put(row, "Count", "bad"); check(p(row, "Count").equals(n(0)), "failed coercion uses typed default");
        c(columns, "Add", "String", text);
        check(p(row, "String").equals(""), "string default");
        fails(() -> c(columns, "Add", "cOuNt")); fails(() -> c(columns, "Get", n(-1)));
        fails(() -> c(table, "Get", n(999))); fails(() -> c(table, "Sort", "Missing"));
        c(table, "Clear"); fails(() -> c(table, "Sort", "Missing"));
        fails(() -> c(columns, "Move", count, n(99))); fails(() -> put(count, "Width", n(-1)));
        check(valueType(columns).equals(type("ValueTableColumnCollection")), "column collection type");
        check(c(number, "ContainsType", type("Число")).equals(true), "type metadata RU/EN identity");
        fails(() -> make("TypeDescription", "Number", n(10)));
        fails(() -> make("TypeDescription", roots("Number")));
        check(c(make("TypeDescription", roots(type("Number"), type("Число"))), "Types") instanceof BArray,
            "array of Type constructor remains available");
    }
    static void graphs() {
        Object table = make("ValueTable"), columns = p(table, "Columns");
        Object first = c(columns, "Add", "Link"), second = c(columns, "Add", "Key");
        c(columns, "Move", second, n(-1)); put(first, "Title", "Link title");
        Object row = c(table, "Add"); put(row, "Link", table); put(row, "Key", n(1));
        Object removed = c(table, "Add"); put(removed, "Link", removed); c(table, "Delete", removed);
        Object indexes = p(table, "Indexes"), index = c(indexes, "Add", "Key");
        Object copy = ValueCopy.snapshot(roots(table, row, columns, first, indexes, index, removed));
        Object copiedTable = at(copy, 0), copiedRow = at(copy, 1), copiedRemoved = at(copy, 6);
        check(at(copiedTable, 0) == copiedRow && p(copiedRow, "Link") == copiedTable, "table graph aliases and cycle");
        check(at(copy, 2) == p(copiedTable, "Columns") && at(at(copy, 2), 1) == at(copy, 3), "column identity after move");
        check(p(at(copy, 3), "Title").equals("Link title"), "column metadata copied");
        check(at(copy, 4) == p(copiedTable, "Indexes") && at(at(copy, 4), 0) == at(copy, 5), "index owner graph");
        check(c(copiedTable, "IndexOf", copiedRemoved).equals(n(-1)) && p(copiedRemoved, "Link") == copiedRemoved, "deleted row remains detached and cycles survive");
        Object list = make("ValueList"), item = c(list, "Add", list, "cycle", true);
        Object listCopy = ValueCopy.snapshot(roots(list, item));
        check(at(at(listCopy, 0), 0) == at(listCopy, 1) && p(at(listCopy, 1), "Value") == at(listCopy, 0), "list item/owner cycle");
        check(c(at(listCopy, 1), "GetID").equals(c(item, "GetID")), "IDs retained by transport");
        Object tree = make("ValueTree"), treeColumns = p(tree, "Columns"), column = c(treeColumns, "Add", "Link");
        Object parent = c(p(tree, "Rows"), "Add"), childRows = p(parent, "Rows"), child = c(childRows, "Add");
        put(parent, "Link", child); put(child, "Link", tree);
        Object treeCopy = ValueCopy.snapshot(roots(child, tree, parent, childRows, treeColumns, column));
        Object copiedTree = at(treeCopy, 1), copiedParent = at(treeCopy, 2), copiedChild = at(treeCopy, 0);
        check(p(copiedChild, "Parent") == copiedParent && c(copiedChild, "Owner") == copiedTree, "standalone child first preserves tree ownership");
        check(p(copiedParent, "Link") == copiedChild && p(copiedChild, "Link") == copiedTree, "tree cell cycles");
        check(p(copiedParent, "Rows") == at(treeCopy, 3) && p(copiedTree, "Columns") == at(treeCopy, 4), "tree collection aliases");
        c(p(tree, "Rows"), "Delete", parent);
        Object detachedCopy = ValueCopy.snapshot(roots(childRows, parent, child));
        Object detachedRows = at(detachedCopy, 0), detachedParent = at(detachedCopy, 1), detachedChild = at(detachedCopy, 2);
        check(p(detachedParent, "Rows") == detachedRows && at(detachedRows, 0) == detachedChild && c(detachedRows, "Count").equals(n(1)), "detached subtree copied once");
        fails(() -> c(tree, "Copy", make("Array")));
    }
    static void collation() throws Exception {
        Object comparison = make("CompareValues");
        check(c(comparison, "Compare", "a", "A").equals(n(0)) && c(comparison, "Compare", "ё", "е").equals(n(1)), "verified Russian collation");
        Object directions = globalValue("sortdirection"), desc = p(directions, "Desc");
        check(ValueCopy.snapshot(desc) == desc && ValueCopy.snapshot(directions) == directions, "immutable sort enums transport");
        check(string(directions).equals("ПеречислениеНаправлениеСортировки")
            && valueType(directions).equals(type("ПеречислениеНаправлениеСортировки")), "enum namespace has BSL presentation/type");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 100; i++) tasks.add(() -> c(comparison, "Compare", "Ё", "ё").equals(n(0)));
            for (Future<Boolean> future : pool.invokeAll(tasks)) check(future.get(), "collator thread isolation");
        } finally { pool.shutdownNow(); }
    }
    static void cli() throws Exception {
        Path root = Files.createTempDirectory("juno-collections-cli-");
        try {
            Path entry = Files.writeString(root.resolve("Main.bsl"), "Т=Новый ТаблицаЗначений;\nТ.Колонки.Добавить(\"Key\");\nТ.Сортировать(\"Missing\");");
            ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
            int code = Main.execute(new String[]{"run", entry.toString()}, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
            check(code == 3 && out.size() == 0 && err.toString(StandardCharsets.UTF_8).contains("Main.bsl:3"), "collection error reports BSL source location");
            Path example = Path.of("examples/коллекции/Программа.bsl");
            out.reset(); err.reset();
            code = Main.execute(new String[]{"run", example.toString()}, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
            check(code == 0 && err.size() == 0 && out.toString(StandardCharsets.UTF_8).contains("Всего: 225"), "working collection example");
        } finally {
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
    }
    public static void main(String[] args) throws Exception {
        indexes(); schemasAndTypedValues(); graphs(); collation(); cli();
        System.out.println("Collection library PASS");
    }
}
