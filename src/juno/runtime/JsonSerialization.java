package juno.runtime;

import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** НастройкиСериализацииJSON: how WriteJSON writes dates (ФорматСериализацииДаты, ВариантЗаписиДаты). */
final class JsonSerialization implements NativeObject, TypedValue {
    private Object format = JsonOptions.DATES.property("iso"), variant = JsonOptions.DATE_VARIANT.property("локальнаядата");
    JsonSerialization(Object[] args) { arity("НастройкиСериализацииJSON", args, 0, 0); }
    JsonDates.Format format() { return JsonDates.format(format); }
    JsonDates.Variant variant() { return JsonDates.variant(variant); }
    public String bslTypeName() { return "НастройкиСериализацииJSON"; }
    public String toString() { return bslTypeName(); }
    public Object property(String name) {
        return switch (name) {
            case "форматсериализациидаты", "dateserializationformat" -> format;
            case "вариантзаписидаты", "datewritingvariant" -> variant;
            default -> missingProperty(bslTypeName(), name);
        };
    }
    public void setProperty(String name, Object value) {
        switch (name) {
            case "форматсериализациидаты", "dateserializationformat" -> { JsonDates.format(value); format = value; }
            case "вариантзаписидаты", "datewritingvariant" -> { JsonDates.variant(value); variant = value; }
            default -> throw error("Свойство НастройкиСериализацииJSON недоступно: " + name);
        }
    }
    public Object invoke(String name, Object[] args) { return missingMethod(bslTypeName(), name); }
}
