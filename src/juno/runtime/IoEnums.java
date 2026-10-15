package juno.runtime;

import java.util.*;
import static juno.runtime.RuntimeSupport.*;

/** Immutable file/stream enums, shared across job snapshots. */
final class IoEnums {
    private IoEnums() {}
    record Value(String typeName, String display, String code) implements TypedValue {
        public String bslTypeName() { return typeName; }
        public String toString() { return display; }
    }
    static final class Namespace implements NativeObject, TypedValue, Iterable<Object> {
        final String name;
        private final Map<String, Value> members = new LinkedHashMap<>();
        private final List<Object> values = new ArrayList<>();
        Namespace(String name, String[][] definitions) {
            this.name = name;
            for (String[] d : definitions) {
                Value v = new Value(name, d[0], d[1]); values.add(v);
                members.put(d[0].toLowerCase(Locale.ROOT), v); members.put(d[1].toLowerCase(Locale.ROOT), v);
            }
        }
        public Object property(String name) { Value v = members.get(name); if (v == null) throw error("Неизвестное значение " + this.name + "." + name); return v; }
        public Object invoke(String name, Object[] args) { throw error("У перечисления нет метода " + name); }
        public Iterator<Object> iterator() { return Collections.unmodifiableList(values).iterator(); }
        public String bslTypeName() { return "Перечисление" + name; }
        public String toString() { return bslTypeName(); }
    }
    static final Namespace ENCODING = new Namespace("КодировкаТекста", new String[][]{
        {"Системная", "System"}, {"ANSI", "ANSI"}, {"OEM", "OEM"}, {"UTF16", "UTF16"}, {"UTF8", "UTF8"}, {"UTF8БезBOM", "UTF8NoBOM"}});
    static final Namespace OPEN = new Namespace("РежимОткрытияФайла", new String[][]{
        {"Дописать", "Append"}, {"Обрезать", "Truncate"}, {"Открыть", "Open"}, {"ОткрытьИлиСоздать", "OpenOrCreate"}, {"Создать", "Create"}, {"СоздатьНовый", "CreateNew"}});
    static final Namespace ACCESS = new Namespace("ДоступКФайлу", new String[][]{{"Запись", "Write"}, {"Чтение", "Read"}, {"ЧтениеИЗапись", "ReadAndWrite"}});
    static final Namespace POSITION = new Namespace("ПозицияВПотоке", new String[][]{{"Начало", "Begin"}, {"Конец", "End"}, {"Текущая", "Current"}});
    static final Namespace BYTE_ORDER = new Namespace("ПорядокБайтов", new String[][]{{"BigEndian", "BigEndian"}, {"LittleEndian", "LittleEndian"}});
    static final Namespace ENV_TARGET = new Namespace("РасположениеПеременнойСреды", new String[][]{{"Процесс", "Process"}, {"Пользователь", "User"}, {"Машина", "Machine"}});
    static final Namespace SIGN=new Namespace("ДопустимыйЗнак",new String[][]{{"Любой","Any"},{"Неотрицательный","Nonnegative"}});
    static final Namespace LENGTH=new Namespace("ДопустимаяДлина",new String[][]{{"Переменная","Variable"},{"Фиксированная","Fixed"}});
    static final Namespace DATE_PARTS=new Namespace("ЧастиДаты",new String[][]{{"Дата","Date"},{"Время","Time"},{"ДатаВремя","DateTime"}});
    static final Namespace ROUND = new Namespace("РежимОкругления",new String[][]{{"Окр15как10","Round15as10"},{"Окр15как20","Round15as20"}});
    static final Namespace HASH = new Namespace("ХешФункция", new String[][]{
        {"CRC32", "CRC32"}, {"MD5", "MD5"}, {"SHA1", "SHA1"}, {"SHA256", "SHA256"}, {"SHA512", "SHA512"}});
    static Object global(String name) {
        return switch (name) {
            case "допустимыйзнак","allowedsign"->SIGN;
            case "допустимаядлина","allowedlength"->LENGTH;
            case "частидаты","datefractions"->DATE_PARTS;
            case "режимокругления", "roundmode" -> ROUND;
            case "хешфункция", "hashfunction" -> HASH;
            case "кодировкатекста", "textencoding" -> ENCODING;
            case "режимоткрытияфайла", "fileopenmode" -> OPEN;
            case "доступкфайлу", "fileaccess" -> ACCESS;
            case "позициявпотоке", "positioninstream", "streamposition" -> POSITION;
            case "порядокбайтов", "byteorder" -> BYTE_ORDER;
            case "расположениепеременнойсреды", "environmentvariabletarget" -> ENV_TARGET;
            default -> null;
        };
    }
    static String code(Object value, Namespace expected) {
        if (value instanceof Value v && v.typeName.equals(expected.name)) return v.code;
        throw error("Ожидалось значение перечисления " + expected.name);
    }
    static BslException error(String message) { return new BslException(message); }
}
