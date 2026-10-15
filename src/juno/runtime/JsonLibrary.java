package juno.runtime;

import java.math.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Public JSON conversion/constructors. Internal strict configuration JSON remains independent. */
final class JsonLibrary {
    private JsonLibrary() {}
    static Object construct(String name,Object[] args) {
        return switch(name) {
            case "чтениеjson","jsonreader" -> new JsonReader(values(args));
            case "записьjson","jsonwriter" -> new JsonWriter(values(args));
            case "параметрызаписиjson","jsonwritersettings" -> new JsonOptions(values(args));
            case "настройкисериализацииjson","jsonserializersettings" -> new JsonSerialization(values(args));
            default -> null;
        };
    }
    static Object global(String name) {
        return switch(name) {
            case "типзначенияjson","jsonvaluetype" -> JsonOptions.VALUE_TYPE;
            case "переносстрокjson","jsonlinebreak" -> JsonOptions.LINES;
            case "экранированиесимволовjson","jsoncharactersescapemode" -> JsonOptions.ESCAPE;
            case "форматдатыjson","jsondateformat" -> JsonOptions.DATES;
            case "вариантзаписидатыjson","jsondatewritingvariant" -> JsonOptions.DATE_VARIANT;
            default -> null;
        };
    }
    static void register(Map<String,GlobalFunctions.Entry> entries) {
        GlobalFunctions.add(entries,"ПрочитатьJSON","ReadJSON",1,4,a -> {
            if(!(a[0] instanceof JsonReader reader)) throw error("ПрочитатьJSON ожидает ЧтениеJSON");
            try {
                boolean map=JsonOptions.bool(optional(a,1,false)); Set<String> dates=new HashSet<>();
                Object properties=optional(a,2,UNDEFINED),format=optional(a,3,UNDEFINED);
                if(!omitted(properties)) {
                    if(properties instanceof String name) dates.add(name);
                    else if(properties instanceof BArray array) {
                        for(Object value:array.values) { if(!(value instanceof String name)) throw error("Имя свойства даты JSON должно быть строкой"); dates.add(name); }
                    } else throw error("Имена свойств дат JSON должны быть строкой или массивом строк");
                } else if(!omitted(format)) throw error("Формат даты JSON требует имен свойств");
                JsonDates.Format dateFormat=omitted(format) ? JsonDates.DEFAULT_FORMAT : JsonDates.format(format);
                if(!nextValue(reader)) throw reader.cursor.invalid("Ожидалось значение");
                Object result=readValue(reader,map,dates,dateFormat); reader.cursor.strictComments=false; reader.tail(); return result;
            } catch(RuntimeException | Error e) { reader.abort(e); throw e; }
        });
        GlobalFunctions.add(entries,"ЗаписатьJSON","WriteJSON",2,3,a -> {
            if(!(a[0] instanceof JsonWriter writer)) throw error("ЗаписатьJSON ожидает ЗаписьJSON");
            try {
                Object given=optional(a,2,UNDEFINED);
                if(!omitted(given) && !(given instanceof JsonSerialization)) throw error("ЗаписатьJSON ожидает НастройкиСериализацииJSON третьим аргументом");
                JsonSerialization settings=given instanceof JsonSerialization s ? s : new JsonSerialization(new Object[0]);
                writeValue(writer,a[1],new IdentityHashMap<>(),0,settings); return UNDEFINED; }
            catch(RuntimeException | Error e) { writer.abort(e); throw e; }
        });
        GlobalFunctions.add(entries,"ЗаписатьДатуJSON","WriteJSONDate",2,3,a -> {
            JsonDates.Variant variant=omitted(optional(a,2,UNDEFINED)) ? JsonDates.DEFAULT_VARIANT : JsonDates.variant(a[2]);
            return JsonDates.write(a[0],JsonDates.format(a[1]),variant);
        });
        GlobalFunctions.add(entries,"ПрочитатьДатуJSON","ReadJSONDate",2,2,a -> JsonDates.read(a[0],JsonDates.format(a[1])));
    }
    private static boolean nextValue(JsonReader reader) {
        if(reader.cursor!=null)reader.cursor.strictComments=true;
        return reader.next();
    }
    private static Object readValue(JsonReader reader,boolean maps,Set<String> dates,JsonDates.Format dateFormat) {
        JsonTokenizer cursor=reader.cursor;
        return switch(cursor.kind) {
            case String, Number, Boolean, Null -> cursor.value;
            case ArrayStart -> {
                BArray result=new BArray();
                while(nextValue(reader)) { if(cursor.kind==JsonTokenizer.Kind.ArrayEnd) break; result.values.add(readValue(reader,maps,dates,dateFormat)); }
                if(cursor.kind!=JsonTokenizer.Kind.ArrayEnd) throw cursor.invalid("Незавершенный массив"); yield result;
            }
            case ObjectStart -> {
                Object result=maps ? new BMap() : new Struct(false);
                while(nextValue(reader)) {
                    if(cursor.kind==JsonTokenizer.Kind.ObjectEnd) break;
                    if(cursor.kind!=JsonTokenizer.Kind.PropertyName) throw cursor.invalid("Ожидалось имя свойства");
                    String name=(String)cursor.value;
                    if(!maps && !identifier(name)) throw cursor.invalid("Недопустимое имя поля структуры: "+name);
                    if(!nextValue(reader)) throw cursor.invalid("Ожидалось значение свойства");
                    Object value=readValue(reader,maps,dates,dateFormat);
                    if(dates.contains(name) && value!=Special.NULL) value=JsonDates.read(value,dateFormat);
                    if(result instanceof BMap map) map.values.put(name,new Entry(name,value)); else ((Struct)result).put(name,value);
                }
                if(cursor.kind!=JsonTokenizer.Kind.ObjectEnd) throw cursor.invalid("Незавершенный объект"); yield result;
            }
            default -> throw cursor.invalid("Ожидалось значение JSON, получено "+cursor.kind);
        };
    }
    private static boolean identifier(String text) {
        if(text.isEmpty()) return false; int start=text.codePointAt(0); if(start!='_' && !Character.isLetter(start)) return false;
        for(int i=Character.charCount(start);i<text.length();) { int c=text.codePointAt(i); if(c!='_' && !Character.isLetterOrDigit(c)) return false; i+=Character.charCount(c); } return true;
    }
    private static void writeValue(JsonWriter writer,Object value,IdentityHashMap<Object,Boolean> path,int depth,JsonSerialization settings) {
        Execution.checkCancellation();
        if(depth>JsonTokenizer.MAX_DEPTH) throw error("JSON: превышена глубина "+JsonTokenizer.MAX_DEPTH);
        if(value instanceof BArray || value instanceof Struct || value instanceof BMap) {
            if(path.put(value,true)!=null) throw error("JSON: циклическая ссылка");
            try {
                if(value instanceof BArray array) {
                    writer.start(false); for(Object item:array.values) writeValue(writer,item,path,depth+1,settings); writer.end(false);
                } else {
                    writer.start(true); Set<String> keys=new HashSet<>();
                    Iterable<Entry> entries=value instanceof Struct s ? s.values.values() : ((BMap)value).values.values();
                    for(Entry entry:entries) {
                        String key=jsonKey(entry.key); if(!keys.add(key)) throw error("JSON: коллизия строковых ключей: "+key);
                        writer.propertyName(key); writeValue(writer,entry.value,path,depth+1,settings);
                    }
                    writer.end(true);
                }
            } finally { path.remove(value); }
        } else if(value instanceof LocalDateTime) writer.primitive(JsonDates.write(value,settings.format(),settings.variant()));
        else writer.primitive(value);
    }
    private static String jsonKey(Object key) {
        if(key instanceof String text)return text;
        throw error("JSON: недопустимый тип значения ключа элемента соответствия");
    }
}
