package juno.runtime;

import java.math.BigDecimal;
import java.util.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;
import static juno.runtime.CollectionSupport.*;

/** Non-interactive ValueList. Item identities survive sorting, moving and copying. */
final class ValueList implements NativeObject, TypedValue, IndexedObject, Iterable<Object> {
    final List<Item> items = new ArrayList<>();
    TypeDescription valueType = new TypeDescription("");
    Object availableValues = UNDEFINED;
    long nextId;

    public String bslTypeName() { return "СписокЗначений"; }
    public String toString() { return bslTypeName(); }
    public Iterator<Object> iterator() { return new LiveIterator(items); }
    public Object getAt(Object value) { return at(items, value); }
    public Object property(String name) {
        return switch (name) {
            case "типзначения", "valuetype" -> valueType;
            case "доступныезначения", "availablevalues" -> availableValues;
            default -> missingProperty(bslTypeName(), name);
        };
    }
    public void setProperty(String name, Object value) {
        switch (name) {
            case "типзначения", "valuetype" -> {
                if (!(value instanceof TypeDescription td)) throw error("ТипЗначения требует ОписаниеТипов");
                valueType = td;
            }
            case "доступныезначения", "availablevalues" -> {
                if (!omitted(value) && !(value instanceof ValueList)) throw error("ДоступныеЗначения требует СписокЗначений или Неопределено");
                availableValues = omitted(value) ? UNDEFINED : value;
            }
            default -> throw error("Свойство списка доступно только для чтения: " + name);
        }
    }
    Object adjusted(Object value) {
        Object result = adjust(valueType, value);
        if (!valueType.types.isEmpty() && availableValues instanceof ValueList allowed
                && allowed.items.stream().noneMatch(item -> eq(item.value, result))) return initial(valueType);
        return result;
    }
    Item item(Object value) {
        if (value instanceof Item item) {
            if (item.owner != this || !items.contains(item)) throw error("Элемент не принадлежит списку значений");
            return item;
        }
        return at(items, value);
    }
    public Object invoke(String name, Object[] raw) {
        Object[] args = values(raw);
        switch (name) {
            case "добавить", "add", "вставить", "insert": {
                boolean insert = name.equals("вставить") || name.equals("insert");
                arguments(name, args, insert ? 1 : 0, insert ? 5 : 4);
                int shift = insert ? 1 : 0;
                int at = insert ? index(args[0]) : items.size();
                if (at < 0 || at > items.size()) throw error("Индекс вставки вне списка значений");
                Object value = adjusted(optional(args, shift, UNDEFINED));
                String presentation = text(optional(args, shift + 1, ""));
                boolean check = bool(optional(args, shift + 2, false));
                Object picture = optional(args, shift + 3, UNDEFINED);
                Item item = new Item(this, nextId++, value, presentation, check, picture);
                items.add(at, item); return item;
            }
            case "получить", "get": arguments(name, args, 1, 1); return at(items, args[0]);
            case "количество", "count": arguments(name, args, 0, 0); return num(items.size());
            case "индекс", "indexof": arguments(name, args, 1, 1); return num(items.indexOf(args[0]));
            case "удалить", "delete": arguments(name, args, 1, 1); items.remove(item(args[0])); return UNDEFINED;
            case "очистить", "clear": arguments(name, args, 0, 0); items.clear(); return UNDEFINED;
            case "сдвинуть", "move": arguments(name, args, 2, 2); move(items, item(args[0]), index(args[1])); return UNDEFINED;
            case "найтипозначению", "findbyvalue": {
                arguments(name, args, 1, 1);
                for (Item item : items) if (eq(item.value, args[0])) return item;
                return UNDEFINED;
            }
            case "найтипоидентификатору", "findbyid": {
                arguments(name, args, 1, 1);
                if (!(args[0] instanceof BigDecimal id)) throw error("Идентификатор требует Число");
                for (Item item : items) if (id.compareTo(BigDecimal.valueOf(item.id)) == 0) return item;
                return UNDEFINED;
            }
            case "выгрузитьзначения", "unloadvalues": {
                arguments(name, args, 0, 0); BArray out = new BArray();
                for (Item item : items) out.values.add(item.value);
                return out;
            }
            case "загрузитьзначения", "loadvalues": {
                arguments(name, args, 1, 1); BArray source = TableLibrary.array(args[0]);
                List<Item> loaded = new ArrayList<>();
                for (Object value : source.values) loaded.add(new Item(this, nextId++, adjusted(value), "", false, UNDEFINED));
                items.clear(); items.addAll(loaded); return UNDEFINED;
            }
            case "заполнитьпометки", "fillchecks": {
                arguments(name, args, 1, 1); boolean check = bool(args[0]);
                for (Item item : items) item.check = check;
                return UNDEFINED;
            }
            case "скопировать", "copy": arguments(name, args, 0, 0); return copy();
            case "сортироватьпозначению", "sortbyvalue", "сортироватьпопредставлению", "sortbypresentation": {
                arguments(name, args, 0, 1);
                Object direction = optional(args, 0, SortDirection.ASCENDING);
                if (!(direction instanceof SortDirection)) throw error("Ожидается НаправлениеСортировки");
                boolean presentation = name.equals("сортироватьпопредставлению") || name.equals("sortbypresentation");
                int sign = direction == SortDirection.DESCENDING ? -1 : 1;
                // 1C orders by the stored presentation (an empty one sorts first) in the full dictionary order of
                // the < operator, and by value like the sorting of tables.
                items.sort((a, b) -> sign * (presentation ? StringOrder.compare(a.presentation, b.presentation) : compare(a.value, b.value)));
                return UNDEFINED;
            }
            default: return missingMethod(bslTypeName(), name);
        }
    }
    ValueList copy() {
        ValueList out = new ValueList();
        out.valueType = valueType; out.availableValues = availableValues; out.nextId = nextId;
        for (Item item : items) out.items.add(new Item(out, item.id, item.value, item.presentation, item.check, item.picture));
        return out;
    }
    static final class Item implements NativeObject, TypedValue {
        final ValueList owner;
        final long id;
        Object value, picture;
        String presentation;
        boolean check;
        Item(ValueList owner, long id, Object value, String presentation, boolean check, Object picture) {
            this.owner = owner; this.id = id; this.value = value; this.presentation = presentation; this.check = check; this.picture = picture;
        }
        public String bslTypeName() { return "ЭлементСпискаЗначений"; }
        public String toString() { return presentation.isEmpty() ? string(value) : presentation; }
        public Object property(String name) {
            return switch (name) {
                case "значение", "value" -> value;
                case "представление", "presentation" -> presentation;
                case "пометка", "check" -> check;
                case "картинка", "picture" -> picture;
                default -> missingProperty(bslTypeName(), name);
            };
        }
        public void setProperty(String name, Object value) {
            switch (name) {
                case "значение", "value" -> this.value = owner.adjusted(value);
                case "представление", "presentation" -> presentation = text(value);
                case "пометка", "check" -> check = bool(value);
                case "картинка", "picture" -> picture = value;
                default -> throw error("Свойство элемента списка доступно только для чтения: " + name);
            }
        }
        public Object invoke(String name, Object[] raw) {
            if (!name.equals("получитьидентификатор") && !name.equals("getid")) return missingMethod(bslTypeName(), name);
            arguments(name, values(raw), 0, 0); return BigDecimal.valueOf(id);
        }
    }
}
