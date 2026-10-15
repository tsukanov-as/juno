package juno.runtime;

import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Small BSL runtime. The compiler and class writer live in BSL. */
public final class RuntimeSupport {
    private RuntimeSupport() {}
    public enum Special { UNDEFINED, NULL, MISSING }
    public static final Object UNDEFINED = Special.UNDEFINED;
    public interface Ref { Object get(); void set(Object value); }
    public static final class Cell implements Ref {
        private Object value;
        public Cell(Object value) { this.value = value; }
        public Object get() { return value; }
        public void set(Object value) { this.value = value; }
    }
    static Ref reference(java.util.function.Supplier<Object> get, java.util.function.Consumer<Object> set) {
        return new Ref() {
            public Object get() { return get.get(); }
            public void set(Object value) { set.accept(value); }
        };
    }
    private static final class StructPropertyRef implements Ref {
        private final Struct owner; private final String name;
        StructPropertyRef(Struct owner, String name) { this.owner = owner; this.name = name; }
        public Object get() {
            Entry entry = owner.values.get(name);
            if (entry == null) throw new BslException("Unknown property: " + name);
            return entry.value;
        }
        public void set(Object value) { writeStruct(owner, name, value); }
    }
    private static final class RowColumnRef implements Ref {
        private final Row row; private final Column column;
        RowColumnRef(Row row, Column column) { this.row = row; this.column = column; }
        public Object get() { return row.get(column); }
        public void set(Object value) { row.set(column, value); }
    }
    public static Object undefined() { return UNDEFINED; }
    public static Object nullValue() { return Special.NULL; }
    public static Object missing() { return Special.MISSING; }
    public static Object cell() { return new Cell(UNDEFINED); }
    public static void checkCancellation() { Execution.checkCancellation(); }
    public static Object required(Object argument) {
        if (argument == Special.MISSING) throw new BslException("Missing required argument");
        return argument;
    }
    public static Object read(Object value) { return value instanceof Ref r ? r.get() : value; }
    public static void assign(Object ref, Object value) { ((Ref) ref).set(read(value)); }
    public static Object parameter(Object argument, Object defaultValue, boolean byValue) {
        if (argument == Special.MISSING) return new Cell(read(defaultValue));
        return !byValue && argument instanceof Ref ? argument : new Cell(read(argument));
    }
    public static Object parameterValue(Object argument, Object defaultValue) {
        return read(argument == Special.MISSING ? defaultValue : argument);
    }
    public static final class BslException extends RuntimeException {
        public BslException(String message) { super(message); }
    }
    public static void raise(Object value) {
        throw new BslException(string(value));
    }
    /** Generated-class entry point; error context is lexical, never shared between calls. */
    @Deprecated public static void saveError(Object error) {}
    /** Generated-class entry point used by bootstrap/seed.jar. */
    @Deprecated public static Object currentError() { return null; }
    /** Compiler entry point: the handler passes its own exception slot. */
    public static Object errorDescription(Object error) { return description((Throwable) error); }
    /** ИнформацияОбОшибке: read-only snapshot; module and line come from the first BSL frame. */
    public static final class ErrorInfo {
        final String description, moduleName, sourceLine;
        final Object line, cause;
        ErrorInfo(String description, String moduleName, Object line, Object cause) {
            this(description,moduleName,line,cause,"");
        }
        ErrorInfo(String description,String moduleName,Object line,Object cause,String sourceLine){
            this.description=description;this.moduleName=moduleName;this.line=line;this.cause=cause;this.sourceLine=sourceLine;
        }
        Object property(String name) {
            return switch (name) {
                case "описание", "description" -> description;
                case "имямодуля", "modulename" -> moduleName;
                case "номерстроки", "linenumber" -> line;
                case "исходнаястрока", "sourceline" -> sourceLine;
                case "причина", "cause" -> cause;
                default -> throw new BslException("Unknown property: " + name);
            };
        }
        public String toString() { return "ИнформацияОбОшибке"; }
    }
    private static final ErrorInfo NO_ERROR = new ErrorInfo("", "", BigDecimal.ZERO, UNDEFINED);
    public static boolean isBslSourceFile(String file) {
        return file != null && file.regionMatches(true, file.length() - 4, ".bsl", 0, 4);
    }
    private static ErrorInfo errorInfoOf(Throwable e) {
        StackTraceElement frame = null;
        for (StackTraceElement candidate : e.getStackTrace()) {
            String file = candidate.getFileName();
            if (isBslSourceFile(file) && candidate.getLineNumber() > 0) { frame = candidate; break; }
        }
        ApplicationContext context=Execution.currentContext();
        ApplicationProgram.Source source=frame==null||context==null?null:context.program().source(frame.getClassName());
        return new ErrorInfo(description(e), source!=null?source.file():frame == null ? "" : frame.getFileName(),
            frame == null ? BigDecimal.ZERO : BigDecimal.valueOf(frame.getLineNumber()),
            e.getCause() instanceof RuntimeException cause ? errorInfoOf(cause) : UNDEFINED,
            source==null?"":source.line(frame.getLineNumber()));
    }
    /** Compiler entry point for ИнформацияОбОшибке() inside a handler. */
    public static Object errorInfo(Object error) { return error == null ? NO_ERROR : errorInfoOf((Throwable) error); }
    private static ErrorInfo errorInfoArgument(Object value) {
        if (read(value) instanceof ErrorInfo info) return info;
        throw new BslException("Несоответствие типов");
    }
    // Platform form "{Модуль(строка)}: текст", causes follow after "по причине:".
    private static String detailDescription(ErrorInfo info) {
        String text = info.moduleName.isEmpty() ? info.description : "{" + info.moduleName + "(" + string(info.line) + ")}: " + info.description;
        return info.cause instanceof ErrorInfo cause ? text + "\nпо причине:\n" + detailDescription(cause) : text;
    }
    // BSL text of an error, without Java class names; platform location prefixes are not reproduced.
    public static String description(Throwable e) {
        if (e == null) return "";
        if (e instanceof BslException) return e.getMessage();
        String message = e.getMessage();
        if (e instanceof ArithmeticException && message != null
            && (message.equals("Division by zero") || message.equals("Division undefined") || message.equals("/ by zero")))
            return "Деление на 0";
        if (e instanceof IndexOutOfBoundsException) return "Индекс находится за границами массива";
        if (e instanceof ClassCastException) return "Несоответствие типов";
        if (e instanceof ArithmeticException) return "Число вне допустимого диапазона";
        if (e instanceof java.nio.charset.CharacterCodingException) return "Недопустимая последовательность символов в указанной кодировке";
        if (e instanceof java.net.http.HttpTimeoutException || e instanceof java.util.concurrent.TimeoutException) return "Истекло время ожидания HTTP";
        if (e instanceof java.io.IOException && message!=null && message.contains("unexpected content length header with 204 response"))
            return "Некорректный HTTP-ответ: код 204 не допускает непустое тело или Transfer-Encoding";
        if (e instanceof java.net.ConnectException) return "Не удалось установить соединение с сервером";
        if (e instanceof java.net.UnknownHostException) return "Не удалось определить адрес сервера: " + message;
        if (e instanceof java.nio.file.NoSuchFileException) return "Файл не найден: " + message;
        if (e instanceof java.nio.file.AccessDeniedException) return "Доступ к файлу запрещён: " + message;
        if (e instanceof java.io.InterruptedIOException || e instanceof InterruptedException) return "Операция прервана";
        if ((message == null || message.isBlank()) && e.getCause() != null) return description(e.getCause());
        return message != null && !message.isBlank() ? message : "Ошибка выполнения операции";
    }
    private static RuntimeException propagate(Throwable e) {
        return e instanceof RuntimeException r ? r : new BslException(e.toString());
    }
    private static String key(Object name) { return (String)lower(name); }
    public static BigDecimal decimal(Object value) {
        value = read(value);
        if (value instanceof BigDecimal n) return n;
        if (value instanceof Boolean b) return b ? BigDecimal.ONE : BigDecimal.ZERO;
        if (value instanceof Number n) return new BigDecimal(n.toString());
        try { return parseNumber(string(value)); }
        catch (NumberFormatException e) { throw new BslException("Cannot convert to Number: " + value); }
    }
    private static BigDecimal parseNumber(String text) {
        String value=text.strip().replace(',','.');
        if(!value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"))throw new BslException("Преобразование значения к типу Число не может быть выполнено");
        return new BigDecimal(value);
    }
    public static Object number(String value) { return new BigDecimal(value); }
    private static int integer(Object value) { try { return decimal(value).setScale(0, RoundingMode.DOWN).intValueExact(); } catch (ArithmeticException e) { throw new BslException("Число вне допустимого диапазона"); } }
    private static BigDecimal num(long value) { return BigDecimal.valueOf(value); }
    public static boolean truth(Object value) {
        value = read(value);
        if (value instanceof Boolean b) return b;
        if (value instanceof BigDecimal n) return n.signum() != 0;
        throw new BslException("Cannot convert to Boolean: " + string(value));
    }
    public static String string(Object value) {
        value = read(value);
        if (value == UNDEFINED || value == Special.MISSING || value == Special.NULL || value == null) return "";
        if (value instanceof BigDecimal n) return n.stripTrailingZeros().toPlainString();
        if (value instanceof Boolean b) return b ? "Да" : "Нет";
        if (value instanceof LocalDateTime d) return BslDates.presentation(d);
        if (value instanceof Type t) return switch(t.name) {
            case "неопределено" -> "Не определено";
            case "двоичныеданные" -> "Двоичные данные";
            case "таблицазначений" -> "Таблица значений";
            case "уникальныйидентификатор" -> "Уникальный идентификатор";
            default -> t.display;
        };
        return value.toString();
    }
    private static Object canonical(Object value) {
        value = read(value);
        return value instanceof BigDecimal n ? n.stripTrailingZeros() : value;
    }
    private static boolean equal(Object a, Object b) {
        a = read(a); b = read(b);
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
        return Objects.equals(a, b);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compare(Object a, Object b) {
        a = read(a); b = read(b);
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y);
        if (a instanceof String x && b instanceof String y) return StringOrder.compare(x, y);
        if (a != null && b != null && a.getClass() == b.getClass() && a instanceof Comparable c) return c.compareTo(b);
        throw new BslException("Incomparable values");
    }
    public static Object unary(Object value, String operation) {
        return switch (operation) {
            case "ЗнакВычитания" -> decimal(value).negate();
            case "ЗнакСложения" -> decimal(value);
            case "Не" -> !truth(value);
            default -> throw new BslException("Unknown unary operation: " + operation);
        };
    }
    public static Object binary(Object a, Object b, String operation) {
        a = read(a); b = read(b);
        return switch (operation) {
            case "ЗнакСложения" -> a instanceof String ? string(a) + string(b) :
                a instanceof LocalDateTime d ? addSeconds(d, decimal(b)) : decimal(a).add(decimal(b));
            case "ЗнакВычитания" -> a instanceof LocalDateTime d ?
                b instanceof LocalDateTime e ? dateDifference(d, e) :
                    addSeconds(d, decimal(b).negate()) : decimal(a).subtract(decimal(b));
            case "ЗнакУмножения" -> decimal(a).multiply(decimal(b));
            case "ЗнакДеления" -> divide(a, b);
            case "ЗнакОстатка" -> decimal(a).remainder(decimal(b));
            case "ЗнакРавно" -> equal(a, b);
            case "ЗнакНеРавно" -> !equal(a, b);
            case "ЗнакМеньше" -> compare(a, b) < 0;
            case "ЗнакБольше" -> compare(a, b) > 0;
            case "ЗнакМеньшеИлиРавно" -> compare(a, b) <= 0;
            case "ЗнакБольшеИлиРавно" -> compare(a, b) >= 0;
            default -> throw new BslException("Unknown binary operation: " + operation);
        };
    }
    // Fixed operators have dedicated JVM entry points; binary dispatches by operator name.
    public static Object add(Object a, Object b) {
        a = read(a); b = read(b);
        if (a instanceof String) return string(a) + string(b);
        if (a instanceof LocalDateTime d) return addSeconds(d, decimal(b));
        return decimal(a).add(decimal(b));
    }
    public static Object subtract(Object a, Object b) {
        a = read(a); b = read(b);
        if (a instanceof LocalDateTime d) return b instanceof LocalDateTime e
            ? dateDifference(d, e) : addSeconds(d, decimal(b).negate());
        return decimal(a).subtract(decimal(b));
    }
    // A date span fits in seconds, but its nanosecond count can overflow a long.
    private static BigDecimal dateDifference(LocalDateTime a, LocalDateTime b) {
        Duration span = Duration.between(b, a);
        return num(span.getSeconds()).add(BigDecimal.valueOf(span.getNano(), 9));
    }
    private static LocalDateTime addSeconds(LocalDateTime date, BigDecimal seconds) {
        BigDecimal whole = seconds.setScale(0, RoundingMode.DOWN);
        long nanos = seconds.subtract(whole).movePointRight(9).longValue();
        return BslDates.checked(date.plusSeconds(whole.longValueExact()).plusNanos(nanos));
    }
    public static Object multiply(Object a, Object b) { a = read(a); b = read(b); return decimal(a).multiply(decimal(b)); }
    public static Object divide(Object a, Object b) {
        BigDecimal x=decimal(a), y=decimal(b);
        BigDecimal estimate=x.divide(y,new MathContext(27,RoundingMode.HALF_UP));
        int whole=Math.max(0,estimate.precision()-estimate.scale());
        return x.divide(y,new MathContext(whole+27,RoundingMode.HALF_UP));
    }
    public static Object remainder(Object a, Object b) {
        a = read(a); b = read(b);
        BigDecimal x = decimal(a), y = decimal(b);
        // Scale zero and at most 18 digits guarantee exact signed-long conversion.
        // Keep BigDecimal for other inputs and zero divisors (including its error).
        if (x.scale() == 0 && y.scale() == 0 && x.precision() <= 18 && y.precision() <= 18 && y.signum() != 0)
            return num(x.longValue() % y.longValue());
        return x.remainder(y);
    }
    public static Object negate(Object a) { return decimal(a).negate(); }
    public static boolean eq(Object a, Object b) { return equal(read(a), read(b)); }
    public static boolean ne(Object a, Object b) { return !equal(read(a), read(b)); }
    public static boolean lt(Object a, Object b) { return compare(read(a), read(b)) < 0; }
    public static boolean gt(Object a, Object b) { return compare(read(a), read(b)) > 0; }
    public static boolean le(Object a, Object b) { return compare(read(a), read(b)) <= 0; }
    public static boolean ge(Object a, Object b) { return compare(read(a), read(b)) >= 0; }
    public static final class Type {
        public final String display;
        private final String name;
        private Type(String name) { display = name; this.name = key(name); }
        public boolean equals(Object other) { return other instanceof Type t && name.equals(t.name); }
        public int hashCode() { return name.hashCode(); }
    }
    // Shared immutable values of builtin types; English names resolve to the same value.
    private static final Map<String, Type> TYPES = new HashMap<>();
    private static Type builtinType(String name, String english) {
        Type type = new Type(name);
        TYPES.put(type.name, type);
        TYPES.put(key(english), type);
        return type;
    }
    private static final Type T_UNDEFINED = builtinType("Неопределено", "Undefined"), T_NULL = builtinType("Null", "Null"),
        T_NUMBER = builtinType("Число", "Number"), T_STRING = builtinType("Строка", "String"),
        T_BOOLEAN = builtinType("Булево", "Boolean"), T_DATE = builtinType("Дата", "Date"), T_TYPE = builtinType("Тип", "Type"),
        T_ARRAY = builtinType("Массив", "Array"), T_FIXED_ARRAY = builtinType("ФиксированныйМассив", "FixedArray"),
        T_FIXED_MAP = builtinType("ФиксированноеСоответствие", "FixedMap"), T_STRUCT = builtinType("Структура", "Structure"),
        T_FIXED_STRUCT = builtinType("ФиксированнаяСтруктура", "FixedStructure"), T_MAP = builtinType("Соответствие", "Map"),
        T_TABLE = builtinType("ТаблицаЗначений", "ValueTable"), T_ROW = builtinType("СтрокаТаблицыЗначений", "ValueTableRow"),
        T_COLUMN = builtinType("КолонкаТаблицыЗначений", "ValueTableColumn"),
        T_NUMBER_QUALIFIER=builtinType("КвалификаторыЧисла","NumberQualifiers"), T_STRING_QUALIFIER=builtinType("КвалификаторыСтроки","StringQualifiers"),
        T_DATE_QUALIFIER=builtinType("КвалификаторыДаты","DateQualifiers"),T_SIGN=builtinType("ДопустимыйЗнак","AllowedSign"),
        T_LENGTH=builtinType("ДопустимаяДлина","AllowedLength"),T_DATE_PARTS=builtinType("ЧастиДаты","DateFractions"),
        T_TYPE_DESCRIPTION = builtinType("ОписаниеТипов", "TypeDescription"), T_BINARY = builtinType("ДвоичныеДанные", "BinaryData"),
        T_ERROR_INFO = builtinType("ИнформацияОбОшибке", "ErrorInfo"),
        T_SEARCH_DIRECTION = builtinType("НаправлениеПоиска", "SearchDirection"),
        T_SEARCH_DIRECTIONS = builtinType("ПеречислениеНаправлениеПоиска", "ПеречислениеНаправлениеПоиска"),
        T_FILE = builtinType("Файл", "File"), T_READER = builtinType("ЧтениеТекста", "TextReader"),
        T_WRITER = builtinType("ЗаписьТекста", "TextWriter"), T_DOCUMENT = builtinType("ТекстовыйДокумент", "TextDocument"),
        T_STREAM = builtinType("Поток", "Stream"), T_FILE_STREAM = builtinType("ФайловыйПоток", "FileStream"),
        T_MEMORY_STREAM = builtinType("ПотокВПамяти", "MemoryStream"), T_BUFFER = builtinType("БуферДвоичныхДанных", "BinaryDataBuffer"),
        T_ROUND = builtinType("РежимОкругления", "RoundMode"), T_ENCODING = builtinType("КодировкаТекста", "TextEncoding"), T_FILE_OPEN = builtinType("РежимОткрытияФайла", "FileOpenMode"),
        T_FILE_ACCESS = builtinType("ДоступКФайлу", "FileAccess"), T_STREAM_POSITION = builtinType("ПозицияВПотоке", "PositionInStream"),
        T_BYTE_ORDER = builtinType("ПорядокБайтов", "ByteOrder"), T_CONSOLE = builtinType("Консоль", "Console"),
        T_MODULES = builtinType("Модули", "Modules"),
        T_ENV_TARGET = builtinType("РасположениеПеременнойСреды", "EnvironmentVariableTarget"),
        T_JSON_READER = builtinType("ЧтениеJSON", "JSONReader"), T_JSON_WRITER = builtinType("ЗаписьJSON", "JSONWriter"),
        T_JSON_SETTINGS = builtinType("ПараметрыЗаписиJSON", "JSONWriterSettings"), T_JSON_SERIALIZER = builtinType("НастройкиСериализацииJSON", "JSONSerializerSettings"), T_JSON_VALUE = builtinType("ТипЗначенияJSON", "JSONValueType"),
        T_JSON_LINES = builtinType("ПереносСтрокJSON", "JSONLineBreak"), T_JSON_ESCAPE = builtinType("ЭкранированиеСимволовJSON", "JSONCharactersEscapeMode"),
        T_JSON_DATE = builtinType("ФорматДатыJSON", "JSONDateFormat"), T_JSON_VARIANT = builtinType("ВариантЗаписиДатыJSON", "JSONDateWritingVariant"),
        T_HTTP_CONNECTION = builtinType("HTTPСоединение", "HTTPConnection"), T_HTTP_REQUEST = builtinType("HTTPЗапрос", "HTTPRequest"),
        T_HTTP_RESPONSE = builtinType("HTTPОтвет", "HTTPResponse"), T_HTTP_PROXY = builtinType("ИнтернетПрокси", "InternetProxy"),
        T_HTTP_SSL = builtinType("ЗащищенноеСоединениеOpenSSL", "OpenSSLSecureConnection"), T_HTTP_CLIENT_CERT = builtinType("СертификатКлиентаФайл", "FileClientCertificate"),
        T_HTTP_CA_CERT = builtinType("СертификатыУдостоверяющихЦентровФайл", "FileCertificationAuthorityCertificates"),
        T_URL_ENCODING = builtinType("СпособКодированияСтроки", "StringEncodingMethod"), T_BOM_USAGE = builtinType("ИспользованиеByteOrderMark", "ByteOrderMarkUsage"),
        T_PROCESS = builtinType("Процесс", "Process"), T_PROCESS_OUT = builtinType("ПотокВыводаТекста", "TextOutputStream"),
        T_PROCESS_IN = builtinType("ПотокВводаТекста", "TextInputStream"),
        T_UUID = builtinType("УникальныйИдентификатор", "UUID"),
        T_RNG = builtinType("ГенераторСлучайныхЧисел", "RandomNumberGenerator"),
        T_HASH = builtinType("ХешированиеДанных", "DataHashing"), T_HASH_FUNCTION = builtinType("ХешФункция", "HashFunction"),
        T_LIST = builtinType("СписокЗначений", "ValueList"), T_LIST_ITEM = builtinType("ЭлементСпискаЗначений", "ValueListItem"),
        T_TREE = builtinType("ДеревоЗначений", "ValueTree"), T_TREE_ROW = builtinType("СтрокаДереваЗначений", "ValueTreeRow"),
        T_TREE_ROWS = builtinType("КоллекцияСтрокДереваЗначений", "ValueTreeRowCollection"),
        T_TREE_COLUMNS = builtinType("КоллекцияКолонокДереваЗначений", "ValueTreeColumnCollection"),
        T_TREE_COLUMN = builtinType("КолонкаДереваЗначений", "ValueTreeColumn"),
        T_TABLE_COLUMNS = builtinType("КоллекцияКолонокТаблицыЗначений", "ValueTableColumnCollection"),
        T_INDEXES = builtinType("ИндексыКоллекции", "CollectionIndexes"), T_INDEX = builtinType("ИндексКоллекции", "CollectionIndex"),
        T_SORT_DIRECTION = builtinType("НаправлениеСортировки", "SortDirection"),
        T_COMPARISON = builtinType("СравнениеЗначений", "CompareValues"),
        T_REGEX_RESULT = builtinType("РезультатПоискаПоРегулярномуВыражению", "ResultOfSearchByRegularExpression"),
        T_REGEX_GROUP = builtinType("ГруппаРезультатаПоискаПоРегулярномуВыражению", "ResultOfSearchByRegularExpressionGroup"),
        T_ASSERTIONS=builtinType("Утверждения","Assertions"), T_TEMP_DIRECTORY=builtinType("ВременныйКаталог","TemporaryDirectory");
    static { TYPES.put("streamposition",T_STREAM_POSITION); }
    private static final Set<String> BUILTIN_TYPE_NAMES=Set.copyOf(TYPES.keySet());
    public static boolean isBuiltinTypeName(String name){return BUILTIN_TYPE_NAMES.contains(name.toLowerCase(Locale.ROOT));}
    private static Type typeNamed(String name) {
        Type type = TYPES.get(key(name));
        return type != null ? type : new Type(name);
    }
    /** Compiler entry points for Тип(...) and ТипЗнч(...) with one argument. */
    public static Object type(Object name) { return typeNamed(string(read(name))); }
    public static Object valueType(Object value) { return typeOf(read(value)); }
    /** Class initializer factory for a Тип("...") literal. */
    public static Object typeConstant(String name) { return typeNamed(name); }
    public static final class TypeDescription implements NativeObject, TypedValue {
        final List<Type> types = new ArrayList<>();
        final TypeQualifiers.Numeric numbers;
        final TypeQualifiers.Text strings;
        final TypeQualifiers.DateParts dates;
        TypeDescription(Object value) {this(new Object[]{value});}
        TypeDescription(Object[] args) {
            numbers=TypeQualifiers.optionalQualifier(args,1,TypeQualifiers.NUMBER,TypeQualifiers.Numeric.class);
            strings=TypeQualifiers.optionalQualifier(args,2,TypeQualifiers.STRING,TypeQualifiers.Text.class);
            dates=TypeQualifiers.optionalQualifier(args,3,TypeQualifiers.DATE,TypeQualifiers.DateParts.class);
            Object value = read(IoSupport.optional(args,0,""));
            if (value instanceof BArray array) {
                for (Object item : array.values) {
                    if (!(read(item) instanceof Type type)) throw new BslException("ОписаниеТипов: массив должен содержать Тип");
                    if (!types.contains(type)) types.add(type);
                }
            } else if (value instanceof String names) {
                for (String name : names.split(",")) if (!name.isBlank()) {
                    Type type = typeNamed(name.trim()); if (!types.contains(type)) types.add(type);
                }
            } else throw new BslException("ОписаниеТипов: ожидается Строка или Массив типов");
        }
        Object initial() { return CollectionSupport.initial(this); }
        public String bslTypeName() { return "ОписаниеТипов"; }
        public String toString() { return bslTypeName(); }
        Object qualify(Object value) {
            if(value instanceof BigDecimal n)return numbers.adjust(n);
            if(value instanceof String s)return strings.adjust(s);
            if(value instanceof LocalDateTime d)return dates.adjust(d);
            return value;
        }
        public Object property(String name) { return switch(name){
            case "квалификаторычисла","numberqualifiers"->numbers;
            case "квалификаторыстроки","stringqualifiers"->strings;
            case "квалификаторыдаты","datequalifiers"->dates;
            default->IoSupport.missingProperty(bslTypeName(),name);
        }; }
        public Object invoke(String name, Object[] raw) {
            Object[] args = IoSupport.values(raw);
            return switch (name) {
                case "типы", "types" -> { IoSupport.arity(name, args, 0, 0); BArray out = new BArray(); out.values.addAll(types); yield out; }
                case "содержиттип", "containstype" -> { IoSupport.arity(name, args, 1, 1); if (!(args[0] instanceof Type)) throw new BslException("ContainsType requires Type"); yield types.contains(args[0]); }
                case "привестизначение", "adjustvalue" -> { IoSupport.arity(name, args, 0, 1); yield CollectionSupport.adjust(this, IoSupport.optional(args, 0, UNDEFINED)); }
                default -> IoSupport.missingMethod(bslTypeName(), name);
            };
        }
    }

    public static final class BArray implements Iterable<Object> {
        final List<Object> values;
        final boolean fixed;
        public BArray() { this(false); }
        BArray(boolean fixed) { values = new ArrayList<>(); this.fixed=fixed; }
        private BArray(int capacity) { values = new ArrayList<>(capacity); fixed=false; }
        void writable() { if(fixed) throw new BslException("Fixed array"); }
        public String toString() { return fixed ? "ФиксированныйМассив" : "Массив"; }
        public Iterator<Object> iterator() { return new LiveIterator(values); }
    }
    public static final class Entry {
        final Object key; Object value;
        Entry(Object key, Object value) { this.key = key; this.value = value; }
    }
    public static class Struct implements Iterable<Object> {
        final LinkedHashMap<String, Entry> values = new LinkedHashMap<>();
        // Only declared original spellings, at most one per field; no query cache.
        final Map<String, String> originalNames = new HashMap<>();
        final boolean fixed;
        Struct(boolean fixed) { this.fixed = fixed; }
        public String toString() { return fixed ? "ФиксированнаяСтруктура" : "Структура"; }
        public Iterator<Object> iterator() { return new ArrayList<Object>(values.values()).iterator(); }
        String resolve(Object name) {
            String text = string(name);
            String normalized = originalNames.get(text);
            return normalized != null ? normalized : key(text);
        }
        void copyFrom(Struct source) {
            values.putAll(source.values);
            originalNames.putAll(source.originalNames);
        }
        void remove(Object name) {
            Entry entry = values.remove(resolve(name));
            if (entry != null) originalNames.remove(entry.key);
        }
        void clear() { values.clear(); originalNames.clear(); }
        void put(String name, Object value) {
            if (fixed) throw new BslException("Fixed structure");
            if(!BslStrings.identifier(name))throw new BslException("Недопустимое имя поля структуры: "+name);
            String normalized = resolve(name);
            Entry old = values.get(normalized);
            if (old == null && !name.equals(normalized)) originalNames.put(name, normalized);
            values.put(normalized, new Entry(old == null ? name : old.key, read(value)));
        }
    }
    public static final class BMap implements Iterable<Object> {
        final LinkedHashMap<Object, Entry> values = new LinkedHashMap<>();
        final boolean fixed, caseInsensitive;
        public BMap() { this(false); }
        BMap(boolean fixed) { this(fixed,false); }
        BMap(boolean fixed,boolean caseInsensitive) { this.fixed=fixed; this.caseInsensitive=caseInsensitive; }
        Object mapKey(Object value) { return caseInsensitive && value instanceof String s ? key(s) : canonical(value); }
        void writable() { if(fixed) throw new BslException("Fixed map"); }
        public String toString() { return fixed ? "ФиксированноеСоответствие" : "Соответствие"; }
        public Iterator<Object> iterator() { return new ArrayList<Object>(values.values()).iterator(); }
    }
    public interface IndexedObject {
        Object getAt(Object index);
        default void setAt(Object index, Object value) { throw new BslException("Read-only collection element"); }
    }
    public static final class Column implements NativeObject, TypedValue {
        String name, normalizedName, title = "";
        final TypeDescription type;
        int ordinal, width;
        final Object owner;
        final Columns columns;
        Column(String name, TypeDescription type, Columns columns) {
            this.name = name; this.normalizedName = key(name); this.type = type == null ? new TypeDescription("") : type;
            this.ordinal = columns.values.size(); this.owner = columns.owner; this.columns = columns;
        }
        public String bslTypeName() { return owner instanceof Table ? "КолонкаТаблицыЗначений" : "КолонкаДереваЗначений"; }
        public String toString() { return bslTypeName(); }
        public Object property(String name) { return CollectionSupport.columnProperty(this, name); }
        public void setProperty(String name, Object value) { CollectionSupport.columnProperty(this, name, value); }
        public Object invoke(String name, Object[] args) { return IoSupport.missingMethod(bslTypeName(), name); }
    }
    public static final class Columns implements Iterable<Object>, NativeObject, TypedValue, IndexedObject {
        final List<Column> values = new ArrayList<>();
        final Map<String,Column> byName = new HashMap<>();
        final Object owner;
        Columns(Object owner) { this.owner = owner; }
        Column find(Object value) {
            value = read(value);
            if (value instanceof Column column) return column.columns == this && values.contains(column) ? column : null;
            if (value instanceof BigDecimal) return CollectionSupport.at(values, value);
            return byName.get(key(value));
        }
        public String bslTypeName() { return owner instanceof Table ? "КоллекцияКолонокТаблицыЗначений" : "КоллекцияКолонокДереваЗначений"; }
        public String toString() { return bslTypeName(); }
        public Object property(String name) {
            Column column = byName.get(name);
            if (column == null) return IoSupport.missingProperty(bslTypeName(), name);
            return column;
        }
        public Object getAt(Object index) { return CollectionSupport.column(this, index); }
        public Object invoke(String name, Object[] args) { return CollectionSupport.columns(this, name, IoSupport.values(args)); }
        public Iterator<Object> iterator() { return new LiveIterator(values); }
    }
    public static final class Row implements NativeObject, TypedValue, IndexedObject, Iterable<Object> {
        private static final Object JAVA_NULL = new Object();
        final Table owner;
        final IdentityHashMap<Column,Object> values = new IdentityHashMap<>();
        Row(Table owner) { this.owner = owner; }
        Object get(Object name) { return get(CollectionSupport.column(owner.columns, read(name))); }
        Object get(Column column) {
            if (column.owner != owner || !owner.columns.values.contains(column)) throw new BslException("Column belongs to another table or was deleted");
            Object value = values.get(column);
            if (value == null) return column.type.initial();
            return value == JAVA_NULL ? null : value;
        }
        void set(Object name, Object value) { set(CollectionSupport.column(owner.columns, read(name)), value); }
        void set(Column column, Object value) {
            if (column.owner != owner || !owner.columns.values.contains(column)) throw new BslException("Column belongs to another table or was deleted");
            value = CollectionSupport.adjust(column.type, read(value));
            values.put(column, value == null ? JAVA_NULL : value);
            owner.changed();
        }
        public Iterator<Object> iterator() {
            List<Object> result = new ArrayList<>();
            for (Column column : owner.columns.values) result.add(get(column));
            return result.iterator();
        }
        public String bslTypeName() { return "СтрокаТаблицыЗначений"; }
        public String toString() { return bslTypeName(); }
        public Object property(String name) { return get(name); }
        public void setProperty(String name, Object value) { set(name, value); }
        public Object getAt(Object index) { return get(index); }
        public void setAt(Object index, Object value) { set(index, value); }
        public Object invoke(String name, Object[] raw) {
            Object[] args = IoSupport.values(raw);
            return switch (name) {
                case "владелец", "owner" -> { CollectionSupport.arguments(name, args, 0, 0); yield owner; }
                case "получить", "get" -> { CollectionSupport.arguments(name, args, 1, 1); yield get(BigDecimal.valueOf(CollectionSupport.index(args[0]))); }
                case "установить", "set" -> { CollectionSupport.arguments(name, args, 2, 2); set(BigDecimal.valueOf(CollectionSupport.index(args[0])), args[1]); yield UNDEFINED; }
                default -> IoSupport.missingMethod(bslTypeName(), name);
            };
        }
    }
    public static final class Table implements Iterable<Object>, NativeObject, TypedValue, IndexedObject {
        final List<Row> rows = new ArrayList<>();
        final Columns columns = new Columns(this);
        final TableLibrary.Indexes indexes = new TableLibrary.Indexes(this);
        long version;
        void changed() {
            version++;
            for (TableLibrary.Index index : indexes.values) index.invalidate();
        }
        public String bslTypeName() { return "ТаблицаЗначений"; }
        public String toString() { return bslTypeName(); }
        public Object property(String name) {
            return switch (name) {
                case "колонки", "columns" -> columns;
                case "индексы", "indexes" -> indexes;
                default -> IoSupport.missingProperty(bslTypeName(), name);
            };
        }
        public Object getAt(Object index) { return CollectionSupport.at(rows, index); }
        public Object invoke(String name, Object[] args) { return TableLibrary.invoke(this, name, IoSupport.values(args)); }
        public Iterator<Object> iterator() { return new LiveIterator(rows); }
    }
    public static final class Binary implements NativeObject {
        public final byte[] bytes;
        Binary(byte[] bytes) { this.bytes = bytes; }
        public Object property(String name) { return IoSupport.missingProperty("ДвоичныеДанные",name); }
        public Object invoke(String name,Object[] args) {
            args=IoSupport.values(args);
            return switch(name) {
                case "размер", "size" -> { IoSupport.arity(name,args,0,0); yield num(bytes.length); }
                case "записать", "write" -> {
                    IoSupport.arity(name,args,1,1);
                    try {
                        if(args[0] instanceof BinarySink sink) BinarySink.write(sink,bytes);
                        else { java.nio.file.Path path=IoSupport.path(args[0]); IoSupport.regular(path); Files.write(path,bytes); }
                        yield UNDEFINED;
                    }
                    catch(java.io.IOException e) { throw IoSupport.failure("Запись двоичных данных",e); }
                }
                default -> IoSupport.missingMethod("ДвоичныеДанные",name);
            };
        }
        public String toString() { return HexFormat.ofDelimiter(" ").withUpperCase().formatHex(bytes); }
    }
    /** ЗавершитьРаботу(Код): an Error, so that Попытка ... Исключение does not catch it. */
    public static final class Exit extends Error {
        public final int code;
        public Exit(int code) { super("ЗавершитьРаботу(" + code + ")", null, false, false); this.code = code; }
    }
    /** ABI implemented by generated BSL classes; all accesses execute their bytecode. */
    public interface Module {
        String __juno_metadata();
        Object __juno_invoke(int member, Object[] arguments);
        Object __juno_call(int member, Object a, Object b, Object c);
        Object __juno_get(int member);
        void __juno_set(int member, Object value);
    }
    /** Explicit adapter for runtime services, never arbitrary Java reflection. */
    public interface NativeObject {
        Object property(String name);
        Object invoke(String name, Object[] arguments);
        default void setProperty(String name, Object value) { throw new BslException("Read-only property: " + name); }
    }
    public interface TypedValue { String bslTypeName(); }
    private record FieldSlot(int index, boolean exported) {}
    // required: minimal argument count; classes before it check required parameters themselves.
    private record MethodSlot(int index, int arity, int required) {}
    private static final class Members {
        final Map<String, FieldSlot> fields = new HashMap<>();
        final Map<String, MethodSlot> methods = new HashMap<>();
        Members(Module module) {
            String description = module.__juno_metadata();
            if (description.isEmpty()) return;
            for (String line : description.split("\n")) {
                String[] parts = line.split("\t", -1);
                if (parts.length != 3 && !(parts.length == 4 && parts[0].equals("M"))) throw new BslException("Invalid module metadata");
                int value = Integer.parseInt(parts[1]);
                if (parts[0].equals("F")) fields.put(parts[2], new FieldSlot(fields.size(), value == 1));
                else if (parts[0].equals("C")) continue; // constructor ПриСозданииОбъекта: read by BackgroundJobs
                else if (parts[0].equals("M")) methods.put(parts[2], new MethodSlot(methods.size(), value,
                    parts.length == 4 ? Integer.parseInt(parts[3]) : 0));
                else throw new BslException("Invalid module member");
            }
        }
    }
    private static final class MemberHolder {
        private volatile Members members;
        Members get(Module module) {
            Members result = members;
            if (result != null) return result;
            synchronized (this) {
                if (members == null) members = new Members(module);
                return members;
            }
        }
    }
    // ClassValue separates different class loaders and does not retain instances.
    private static final ClassValue<MemberHolder> MEMBERS = new ClassValue<>() {
        protected MemberHolder computeValue(Class<?> type) { return new MemberHolder(); }
    };
    private static Module module(Object object) {
        if (object instanceof Module module) return module;
        throw new BslException("Значение не является значением объектного типа");
    }
    private static Members members(Module module) { return MEMBERS.get(module.getClass()).get(module); }
    private static FieldSlot publicField(Module module, String name) {
        FieldSlot slot = members(module).fields.get(name);
        if (slot == null || !slot.exported) throw new BslException("Unknown public field: " + name);
        return slot;
    }
    /** Named exported variables only; used by FillPropertyValues without host reflection. */
    static Map<String,Ref> moduleProperties(Module module) {
        Map<String,Ref> fields = new LinkedHashMap<>();
        members(module).fields.entrySet().stream().filter(entry -> entry.getValue().exported)
            .sorted(Comparator.comparingInt(entry -> entry.getValue().index))
            .forEach(entry -> fields.put(entry.getKey(), new ModuleFieldRef(module, entry.getValue().index)));
        return fields;
    }
    private static final class ModuleFieldRef implements Ref {
        private final Module owner;
        private final int index;
        ModuleFieldRef(Module owner, int index) { this.owner = owner; this.index = index; }
        public Object get() { return owner.__juno_get(index); }
        public void set(Object value) { owner.__juno_set(index, value); }
    }
    public static Object fieldAt(Object receiver, int index) {
        return new ModuleFieldRef(module(read(receiver)), index);
    }
    public static Object field(Object receiver, String name) {
        Module object = module(read(receiver));
        FieldSlot slot = members(object).fields.get(key(name));
        if (slot == null) throw new BslException("Unknown field: " + name);
        return new ModuleFieldRef(object, slot.index);
    }
    public static Object property(Object receiver, String name) {
        return propertyNamed(receiver, key(name));
    }
    public static Object getPropertyNamed(Object receiver, String name) {
        Object object = read(receiver);
        if (object instanceof Struct s) {
            Entry entry = s.values.get(name);
            if (entry == null) throw new BslException("Unknown property: " + name);
            return entry.value;
        }
        if (object instanceof Row r) {
            Column column = r.owner.columns.byName.get(name);
            if (column == null) throw new BslException("Unknown column: " + name);
            return r.get(column);
        }
        if (object instanceof Table table && name.equals("колонки")) return table.columns;
        if (object instanceof Entry entry) return entryProperty(entry, name);
        if (object instanceof Column column) return column.property(name);
        if (object instanceof ErrorInfo info) return info.property(name);
        if (object instanceof NativeObject nativeObject) return nativeObject.property(name);
        if (name.equals("этотобъект") || name.equals("thisobject")) return object;
        Module module = module(object);
        return module.__juno_get(publicField(module, name).index);
    }
    /** Compiler entry point: name is already normalized at compile time. */
    public static Object propertyNamed(Object receiver, String name) {
        Object object = read(receiver);
        if (object instanceof NativeObject nativeObject) {
            nativeObject.property(name); // Validate before returning a reference, including assignment targets.
            return reference(() -> nativeObject.property(name), v -> nativeObject.setProperty(name, read(v)));
        }
        if (object instanceof Struct s) return new StructPropertyRef(s, name);
        if (object instanceof Row r) {
            Column c = r.owner.columns.byName.get(name);
            if (c == null) throw new BslException("Unknown column: " + name);
            return new RowColumnRef(r, c);
        }
        Object value = UNDEFINED;
        if (object instanceof Table t && name.equals("колонки")) value = t.columns;
        else if (object instanceof Entry e) value = entryProperty(e, name);
        else if (object instanceof Column c) value = switch (name) { case "имя" -> c.name; case "типзначения" -> c.type; default -> throw new BslException(name); };
        else if (object instanceof ErrorInfo info) value = info.property(name);
        else if (object instanceof NativeObject nativeObject) value = nativeObject.property(name);
        else if (name.equals("этотобъект") || name.equals("thisobject")) value = object;
        else {
            Module module = module(object);
            return new ModuleFieldRef(module, publicField(module, name).index);
        }
        Object result = value;
        return reference(() -> result, v -> { throw new BslException("Read-only property: " + name); });
    }
    private static Object entryProperty(Entry entry, String name) {
        return switch (name) {
            case "ключ", "key" -> entry.key;
            case "значение", "value" -> entry.value;
            default -> throw new BslException("Unknown KeyAndValue property: " + name);
        };
    }
    public static Object index(Object receiver, Object index) {
        Object object = read(receiver); Object k = read(index);
        if (object instanceof IndexedObject indexed) {
            indexed.getAt(k);
            return reference(() -> indexed.getAt(k), v -> indexed.setAt(k, v));
        }
        if (object instanceof BinaryBuffer b) { int i=IoSupport.integer(k); b.get(i); return reference(() -> b.get(i), v -> b.set(i,v)); }
        if (object instanceof BArray a) return reference(() -> a.values.get(integer(k)), v -> { a.writable(); a.values.set(integer(k), v); });
        if (object instanceof BMap m) return reference(() -> {
            Entry e = m.values.get(m.mapKey(k)); return e == null ? UNDEFINED : e.value;
        }, v -> { m.writable(); m.values.put(m.mapKey(k), new Entry(k, v)); });
        if (object instanceof Row row) return reference(() -> row.get(k), v -> row.set(k, v));
        if (object instanceof Struct s) return propertyNamed(s, s.resolve(k));
        if (object instanceof Table t) return new Cell(t.rows.get(integer(k)));
        if (object instanceof Columns c) return new Cell(c.find(k));
        throw new BslException("Cannot index: " + object);
    }
    public static Object getIndex(Object receiver, Object index) {
        Object object = read(receiver), k = read(index);
        if (object instanceof IndexedObject indexed) return indexed.getAt(k);
        if (object instanceof BinaryBuffer b) return b.get(IoSupport.integer(k));
        if (object instanceof BArray array) return array.values.get(integer(k));
        if (object instanceof BMap map) { Entry entry = map.values.get(map.mapKey(k)); return entry == null ? UNDEFINED : entry.value; }
        if (object instanceof Row row) return row.get(k);
        if (object instanceof Struct s) return getPropertyNamed(s, s.resolve(k));
        if (object instanceof Table table) return table.rows.get(integer(k));
        if (object instanceof Columns columns) return columns.find(k);
        throw new BslException("Cannot index: " + object);
    }
    /** Resolve the assignment target before the RHS without allocating a Ref. */
    public static Object prepareProperty(Object object, String name) {
        if (object instanceof Struct) return name; // existence is checked on write
        if (object instanceof Row row) {
            Column column = row.owner.columns.byName.get(name);
            if (column == null) throw new BslException("Unknown column: " + name);
            return column;
        }
        if (object instanceof Module module && !name.equals("этотобъект") && !name.equals("thisobject")) return publicField(module, name);
        return propertyNamed(object, name); // rare read-only/unsupported targets
    }
    public static void writeProperty(Object object, Object target, Object value) {
        value = read(value);
        if (target instanceof Ref ref) { ref.set(value); return; }
        if (object instanceof Struct s) writeStruct(s, (String) target, value);
        else if (object instanceof Row row) row.set((Column) target, value);
        else if (object instanceof Module module) module.__juno_set(((FieldSlot) target).index, value);
        else ((Ref) target).set(value);
    }
    private static void writeStruct(Struct s, String name, Object value) {
        Entry old = s.values.get(name);
        if (old == null) throw new BslException("Unknown property: " + name);
        if (s.fixed) throw new BslException("Fixed structure");
        s.values.put(name, new Entry(old.key, read(value)));
    }
    public static Object prepareIndex(Object object, Object index) {
        Object k = read(index);
        if (object instanceof Struct s) return s.resolve(k);
        if (object instanceof BArray || object instanceof BMap || object instanceof Row) return k;
        return index(object, k);
    }
    public static void writeIndex(Object object, Object target, Object value) {
        value = read(value);
        if (object instanceof BArray a) { a.writable(); a.values.set(integer(target), value); }
        else if (object instanceof BMap m) { m.writable(); m.values.put(m.mapKey(target), new Entry(target, value)); }
        else if (object instanceof Row row) row.set(target, value);
        else if (object instanceof Struct s) writeStruct(s, (String) target, value);
        else ((Ref) target).set(value);
    }
    public static Object iterator(Object value) {
        Object object = read(value);
        if (object instanceof Iterable<?> i) return i.iterator();
        throw new BslException("Not a collection: " + string(object));
    }
    public static boolean hasNext(Object iterator) { return ((Iterator<?>) iterator).hasNext(); }
    public static Object next(Object iterator) { return ((Iterator<?>) iterator).next(); }
    public static Object constructDynamic(Object[] args) {
        if (args.length < 1 || args.length > 2) throw new BslException("New expects a type and optional argument array");
        Object values = args.length == 2 ? read(args[1]) : UNDEFINED;
        return construct(string(args[0]), values == UNDEFINED || values == Special.MISSING ? new Object[0] : ((BArray) values).values.toArray());
    }
    public static Object construct(String name, Object[] args) {
        if(key(name).equals("модули") || key(name).equals("modules")) throw new BslException("Модули — глобальное свойство, конструктор не поддерживается");
        if(key(name).equals("утверждения")||key(name).equals("assertions")){IoSupport.arity(name,args,0,0);return new Assertions();}
        if(key(name).equals("временныйкаталог")||key(name).equals("temporarydirectory"))return new TemporaryDirectory(IoSupport.values(args));
        if(key(name).equals("процесс")||key(name).equals("process")) throw new BslException("Процесс создаётся через CreateProcess");
        Object qualifier=TypeQualifiers.construct(key(name),args); if(qualifier!=null)return qualifier;
        Object helper = HelperLibrary.construct(key(name), args);
        if (helper != null) return helper;
        Object collection = CollectionSupport.construct(key(name), args);
        if (collection != null) return collection;
        Object http=HttpLibrary.construct(key(name),args); if(http!=null) return http;
        Object json=JsonLibrary.construct(key(name),args);
        if(json!=null) return json;
        Object io = IoLibrary.construct(key(name), args);
        if (io != null) return io;
        return switch (key(name)) {
            case "массив", "array" -> {
                BArray array;
                if(args.length==1 && read(args[0]) instanceof BArray source && source.fixed) {
                    array=new BArray(); array.values.addAll(source.values); yield array;
                }
                if (args.length > 0) {
                    int count = integer(args[0]);
                    array = new BArray(Math.max(0, count));
                    for (int i = 0; i < count; i++) array.values.add(args.length > 1 ? construct("массив", Arrays.copyOfRange(args, 1, args.length)) : UNDEFINED);
                } else array = new BArray();
                yield array;
            }
            case "структура", "structure", "фиксированнаяструктура", "fixedstructure" -> {
                boolean fixed = key(name).contains("фиксированная") || key(name).equals("fixedstructure");
                Struct s = new Struct(false);
                if (args.length > 0 && read(args[0]) instanceof Struct source) s.copyFrom(source);
                else if (args.length > 0 && !string(args[0]).isEmpty()) {
                    String[] names = string(args[0]).split(",");
                    for (int i = 0; i < names.length; i++) s.put(names[i].trim(), i + 1 < args.length ? read(args[i + 1]) : UNDEFINED);
                }
                if (!fixed) yield s;
                Struct immutable = new Struct(true); immutable.copyFrom(s); yield immutable;
            }
            case "фиксированныймассив", "fixedarray" -> {
                if(args.length!=1 || !(read(args[0]) instanceof BArray source) || source.fixed) throw new BslException("FixedArray expects an Array");
                BArray fixed=new BArray(true); fixed.values.addAll(source.values); yield fixed;
            }
            case "соответствие", "map", "фиксированноесоответствие", "fixedmap" -> {
                boolean fixed=key(name).equals("fixedmap") || key(name).equals("фиксированноесоответствие");
                if(args.length==0 && !fixed) yield new BMap();
                if(args.length!=1 || !(read(args[0]) instanceof BMap source) || source.fixed==fixed) throw new BslException("Map/FixedMap expects the opposite mutable/fixed map");
                BMap result=new BMap(fixed,source.caseInsensitive);
                source.values.forEach((k,e) -> result.values.put(k,new Entry(e.key,e.value))); yield result;
            }
            case "таблицазначений", "valuetable" -> new Table();
            case "описаниетипов", "typedescription" -> {
                IoSupport.arity(name, args, 0, 4);
                yield new TypeDescription(IoSupport.values(args));
            }
            default -> {
                BackgroundJobs application = Execution.currentApplication();
                if (application == null) throw new BslException("Unsupported constructor: " + name);
                yield application.newModule(name, args);
            }
        };
    }
    public static Object call(Object receiver, String name, Object[] args) {
        return callNamed(receiver, key(name), args);
    }
    private enum Builtin {
        ARRAY_ADD, ARRAY_INSERT, ARRAY_COUNT, ARRAY_BOUND, ARRAY_CLEAR, ARRAY_DELETE, ARRAY_FIND, ARRAY_GET, ARRAY_SET,
        STRUCT_INSERT, STRUCT_PROPERTY, STRUCT_COUNT, STRUCT_DELETE, STRUCT_CLEAR,
        MAP_INSERT, MAP_GET, MAP_COUNT, MAP_CLEAR, MAP_DELETE,
        COLUMNS_ADD, COLUMNS_FIND, COLUMNS_COUNT, ROW_OWNER,
        TABLE_ADD, TABLE_COUNT, TABLE_CLEAR, TABLE_FILL, TABLE_SORT, TABLE_FIND,
        BINARY_WRITE, MODULE, NATIVE
    }
    private static Builtin resolveBuiltin(Object object, String name) {
        if (object instanceof NativeObject) return Builtin.NATIVE;
        if (object instanceof BArray) return switch (name) {
            case "добавить", "add" -> Builtin.ARRAY_ADD;
            case "вставить", "insert" -> Builtin.ARRAY_INSERT;
            case "количество", "count" -> Builtin.ARRAY_COUNT;
            case "вграница", "ubound" -> Builtin.ARRAY_BOUND;
            case "очистить", "clear" -> Builtin.ARRAY_CLEAR;
            case "удалить", "delete" -> Builtin.ARRAY_DELETE;
            case "найти", "find" -> Builtin.ARRAY_FIND;
            case "получить", "get" -> Builtin.ARRAY_GET;
            case "установить", "set" -> Builtin.ARRAY_SET;
            default -> throw new BslException("Array method: " + name);
        };
        if (object instanceof Struct) return switch (name) {
            case "вставить", "insert" -> Builtin.STRUCT_INSERT;
            case "свойство", "property" -> Builtin.STRUCT_PROPERTY;
            case "количество", "count" -> Builtin.STRUCT_COUNT;
            case "удалить", "delete" -> Builtin.STRUCT_DELETE;
            case "очистить", "clear" -> Builtin.STRUCT_CLEAR;
            default -> throw new BslException("Structure method: " + name);
        };
        if (object instanceof BMap) return switch (name) {
            case "вставить", "insert" -> Builtin.MAP_INSERT;
            case "получить", "get" -> Builtin.MAP_GET;
            case "количество", "count" -> Builtin.MAP_COUNT;
            case "очистить", "clear" -> Builtin.MAP_CLEAR;
            case "удалить", "delete" -> Builtin.MAP_DELETE;
            default -> throw new BslException("Map method: " + name);
        };
        if (object instanceof Columns) return switch (name) {
            case "добавить", "add" -> Builtin.COLUMNS_ADD;
            case "найти", "find" -> Builtin.COLUMNS_FIND;
            case "количество", "count" -> Builtin.COLUMNS_COUNT;
            default -> throw new BslException("Columns method: " + name);
        };
        if (object instanceof Row && name.equals("владелец")) return Builtin.ROW_OWNER;
        if (object instanceof Table) return switch (name) {
            case "добавить", "add" -> Builtin.TABLE_ADD;
            case "количество", "count" -> Builtin.TABLE_COUNT;
            case "очистить", "clear" -> Builtin.TABLE_CLEAR;
            case "заполнитьзначения" -> Builtin.TABLE_FILL;
            case "сортировать" -> Builtin.TABLE_SORT;
            case "найтистроки" -> Builtin.TABLE_FIND;
            default -> throw new BslException("Table method: " + name);
        };
        if (object instanceof Binary && name.equals("записать")) return Builtin.BINARY_WRITE;
        module(object); // validate an unsupported receiver before linking
        return Builtin.MODULE;
    }
    private static Object invokeBuiltin(Builtin target, Object object, Object a, Object b, Object out, int count) {
        switch(target) {
            case ARRAY_ADD, ARRAY_INSERT, ARRAY_CLEAR, ARRAY_DELETE, ARRAY_SET -> ((BArray)object).writable();
            case MAP_INSERT, MAP_CLEAR, MAP_DELETE -> ((BMap)object).writable();
            default -> { }
        }
        return switch (target) {
            case ARRAY_GET -> { if(count!=1) throw new BslException("Array.Get expects 1 argument"); yield ((BArray)object).values.get(integer(a)); }
            case ARRAY_SET -> { if(count!=2) throw new BslException("Array.Set expects 2 arguments"); ((BArray)object).values.set(integer(a),b); yield UNDEFINED; }
            case ARRAY_ADD -> { ((BArray) object).values.add(a); yield UNDEFINED; }
            case ARRAY_INSERT -> { ((BArray) object).values.add(integer(a), b); yield UNDEFINED; }
            case ARRAY_COUNT -> num(((BArray) object).values.size());
            case ARRAY_BOUND -> num(((BArray) object).values.size() - 1);
            case ARRAY_CLEAR -> { ((BArray) object).values.clear(); yield UNDEFINED; }
            case ARRAY_DELETE -> { ((BArray) object).values.remove(integer(a)); yield UNDEFINED; }
            case ARRAY_FIND -> {
                BArray array = (BArray) object; int index = -1;
                for (int i = 0; i < array.values.size(); i++) if (equal(array.values.get(i), a)) { index = i; break; }
                yield index < 0 ? UNDEFINED : num(index);
            }
            case STRUCT_INSERT -> { ((Struct) object).put(string(a), b); yield UNDEFINED; }
            case STRUCT_PROPERTY -> {
                Struct s = (Struct) object; Entry e = s.values.get(s.resolve(a));
                if (count > 1 && out instanceof Ref ref) ref.set(e == null ? UNDEFINED : e.value);
                yield e != null;
            }
            case STRUCT_COUNT -> num(((Struct) object).values.size());
            case STRUCT_DELETE -> { Struct s = (Struct) object; if (s.fixed) throw new BslException("Fixed structure"); s.remove(a); yield UNDEFINED; }
            case STRUCT_CLEAR -> { Struct s = (Struct) object; if (s.fixed) throw new BslException("Fixed structure"); s.clear(); yield UNDEFINED; }
            case MAP_INSERT -> { BMap m=(BMap)object; m.values.put(m.mapKey(a),new Entry(a,b)); yield UNDEFINED; }
            case MAP_GET -> { BMap m=(BMap)object; Entry e=m.values.get(m.mapKey(a)); yield e == null ? UNDEFINED : e.value; }
            case MAP_COUNT -> num(((BMap) object).values.size());
            case MAP_CLEAR -> { ((BMap) object).values.clear(); yield UNDEFINED; }
            case MAP_DELETE -> { BMap m=(BMap)object; m.values.remove(m.mapKey(a)); yield UNDEFINED; }
            case COLUMNS_ADD -> {
                Columns columns = (Columns) object;
                if (columns.find(a) != null) throw new BslException("Duplicate column");
                Column column = new Column(string(a), b instanceof TypeDescription td ? td : null, columns);
                columns.values.add(column); columns.byName.put(column.normalizedName, column); yield column;
            }
            case COLUMNS_FIND -> { Column c = ((Columns) object).find(a); yield c == null ? UNDEFINED : c; }
            case COLUMNS_COUNT -> num(((Columns) object).values.size());
            case ROW_OWNER -> ((Row) object).owner;
            case TABLE_ADD -> { Table table = (Table) object; Row row = new Row(table); table.rows.add(row); yield row; }
            case TABLE_COUNT -> num(((Table) object).rows.size());
            case TABLE_CLEAR -> { ((Table) object).rows.clear(); yield UNDEFINED; }
            case TABLE_FILL -> { Table table = (Table) object; for (Row r : table.rows) for (Column c : table.columns.values) r.set(c, a); yield UNDEFINED; }
            case TABLE_SORT -> {
                sortTable((Table) object, string(a));
                yield UNDEFINED;
            }
            case TABLE_FIND -> {
                Struct filter = (Struct) a; BArray result = new BArray();
                for (Row r : ((Table) object).rows) {
                    boolean found = true;
                    for (Entry e : filter.values.values()) if (!equal(r.get(e.key), e.value)) { found = false; break; }
                    if (found) result.values.add(r);
                }
                yield result;
            }
            case BINARY_WRITE -> ((Binary)object).invoke("write",new Object[]{a});
            case MODULE -> throw new AssertionError("Module dispatched as builtin");
            case NATIVE -> throw new AssertionError("Native service dispatched without arguments");
        };
    }
    private static void sortTable(Table table, String specification) {
        String[] keys = specification.split(",");
        String[] names = new String[keys.length];
        boolean[] descending = new boolean[keys.length];
        Column[] columns = new Column[keys.length];
        for (int i = 0; i < keys.length; i++) {
            String[] parts = keys[i].trim().split("\\s+");
            names[i] = parts[0];
            descending[i] = parts.length > 1 && key(parts[1]).startsWith("убыв");
        }
        table.rows.sort((x, y) -> {
            for (int i = 0; i < names.length; i++) {
                Column column = columns[i];
                if (column == null) {
                    column = table.columns.find(names[i]);
                    // Resolve lazily: empty tables and keys after a decisive key
                    // must retain the existing error timing.
                    if (column == null) throw new BslException("Unknown column: " + names[i]);
                    columns[i] = column;
                }
                int compared = compare(x.get(column), y.get(column));
                if (compared != 0) return descending[i] ? -compared : compared;
            }
            return 0;
        });
    }
    private static MethodSlot resolveMethod(Module module, String name) {
        MethodSlot method = members(module).methods.get(name);
        if (method == null) throw new BslException("Unknown public method: " + name + " on " + module.getClass().getName());
        return method;
    }
    private static Object invokeModule(Module module, MethodSlot target, Object[] args, String name) {
        if (args.length > target.arity) throw new BslException("Too many arguments: " + name);
        if (args.length < target.required) throw new BslException("Not enough arguments: " + name);
        Object[] actual = args;
        if (args.length < target.arity) {
            actual = Arrays.copyOf(args, target.arity);
            Arrays.fill(actual, args.length, actual.length, Special.MISSING);
        }
        return module.__juno_invoke(target.index, actual);
    }
    static void validateCall(Module module, String name, int arguments) {
        MethodSlot target = resolveMethod(module, name);
        if (arguments < target.required || arguments > target.arity) throw new BslException("Неверное число параметров метода задания: " + name);
    }
    public static Object callNamed(Object receiver, String name, Object[] args) {
        Object object = read(receiver);
        if (object instanceof NativeObject nativeObject) return nativeObject.invoke(name, args);
        Object a = args.length > 0 ? read(args[0]) : UNDEFINED;
        Object b = args.length > 1 ? read(args[1]) : UNDEFINED;
        Builtin target = resolveBuiltin(object, name);
        if (target != Builtin.MODULE) return invokeBuiltin(target, object, a, b, args.length > 1 ? args[1] : UNDEFINED, args.length);
        Module module = module(object);
        return invokeModule(module, resolveMethod(module, name), args, name);
    }
    /** A compiler-created site: two guarded targets plus a class-unloading-safe fallback. */
    public static final class MethodSite {
        private final String name;
        private record Target(java.lang.ref.WeakReference<Class<?>> type, Builtin builtin, MethodSlot method) {}
        private static final class Holder { volatile Target target; }
        private final ClassValue<Holder> targets = new ClassValue<>() {
            protected Holder computeValue(Class<?> type) { return new Holder(); }
        };
        private volatile Target first, second;
        public MethodSite(String name) { this.name = name; }
        private Target target(Object object) {
            Class<?> type = object.getClass(); Target t = first;
            if (t != null && t.type.get() == type) return t;
            t = second;
            if (t != null && t.type.get() == type) return t;
            Holder holder = targets.get(type); t = holder.target;
            if (t == null) synchronized (holder) {
                t = holder.target;
                if (t == null) {
                    Builtin builtin = resolveBuiltin(object, name);
                    t = new Target(new java.lang.ref.WeakReference<>(type), builtin,
                        builtin == Builtin.MODULE ? resolveMethod((Module) object, name) : null);
                    holder.target = t;
                }
            }
            second = first; first = t;
            return t;
        }
        private Object small(Object receiver, Object a0, Object a1, Object a2, int count) {
            Object object = read(receiver);
            Object a = count > 0 ? read(a0) : UNDEFINED;
            Object b = count > 1 ? read(a1) : UNDEFINED;
            Target t = target(object);
            if (t.builtin == Builtin.NATIVE) return ((NativeObject) object).invoke(name,
                count == 0 ? new Object[0] : count == 1 ? new Object[]{a0} : count == 2 ? new Object[]{a0, a1} : new Object[]{a0, a1, a2});
            if (t.builtin != Builtin.MODULE) return invokeBuiltin(t.builtin, object, a, b, a1, count);
            if (count > t.method.arity) throw new BslException("Too many arguments: " + name);
            if (count < t.method.required) throw new BslException("Not enough arguments: " + name);
            if (t.method.arity <= 3) return ((Module) object).__juno_call(t.method.index, a0, a1, a2);
            Object[] args = new Object[count];
            if (count > 0) args[0] = a0;
            if (count > 1) args[1] = a1;
            if (count > 2) args[2] = a2;
            return invokeModule((Module) object, t.method, args, name);
        }
        public Object call0(Object receiver) { return small(receiver, Special.MISSING, Special.MISSING, Special.MISSING, 0); }
        public Object call1(Object receiver, Object a) { return small(receiver, a, Special.MISSING, Special.MISSING, 1); }
        public Object call2(Object receiver, Object a, Object b) { return small(receiver, a, b, Special.MISSING, 2); }
        public Object call3(Object receiver, Object a, Object b, Object c) { return small(receiver, a, b, c, 3); }
        public Object call(Object receiver, Object[] args) {
            Object object = read(receiver);
            Object a = args.length > 0 ? read(args[0]) : UNDEFINED;
            Object b = args.length > 1 ? read(args[1]) : UNDEFINED;
            Target t = target(object);
            if (t.builtin == Builtin.NATIVE) return ((NativeObject) object).invoke(name, args);
            if (t.builtin != Builtin.MODULE) return invokeBuiltin(t.builtin, object, a, b, args.length > 1 ? args[1] : UNDEFINED, args.length);
            return invokeModule((Module) object, t.method, args, name);
        }
    }
    /** Class initializer entry: one MethodSite per call site, names separated by a line feed. */
    public static MethodSite[] sites(String names) {
        String[] parts = names.split("\n", -1);
        MethodSite[] result = new MethodSite[parts.length];
        for (int i = 0; i < parts.length; i++) result[i] = new MethodSite(parts[i]);
        return result;
    }
    public static Object date(String text) {
        // The compiler passes the original quoted literal. Public Date(string)
        // accepts compact digits; presentation and ISO parsing have separate APIs.
        boolean literal = text.length() >= 2 && text.startsWith("'") && text.endsWith("'");
        String source = literal ? text.substring(1, text.length() - 1) : text;
        if (!(literal ? source.matches("[0-9 .:/-]+") : source.matches("[0-9]+")))
            throw new BslException("Дата: ожидается компактная строка ггггММдд[ЧЧмм[сс]]");
        String digits = literal ? source.replaceAll("[ .:/-]", "") : source;
        if (digits.length() != 8 && digits.length() != 12 && digits.length() != 14)
            throw new BslException("Дата: ожидается 8, 12 или 14 цифр");
        if (digits.equals("00000000") || digits.equals("000000000000") || digits.equals("00000000000000")) return LocalDateTime.of(1, 1, 1, 0, 0);
        if (digits.length() == 8) digits += "000000";
        if (digits.length() == 12) digits += "00";
        return BslDates.checked(LocalDateTime.parse(digits, DateTimeFormatter.ofPattern("uuuuMMddHHmmss", Locale.ROOT)
            .withResolverStyle(java.time.format.ResolverStyle.STRICT)));
    }
    private static Type typeOf(Object value) {
        value = read(value);
        return value == UNDEFINED ? T_UNDEFINED : value == Special.NULL ? T_NULL :
            value instanceof BigDecimal ? T_NUMBER : value instanceof String ? T_STRING : value instanceof Boolean ? T_BOOLEAN :
            value instanceof LocalDateTime ? T_DATE : value instanceof UUID ? T_UUID : value instanceof Type ? T_TYPE : value instanceof BArray a ? a.fixed ? T_FIXED_ARRAY : T_ARRAY :
            value instanceof Struct s ? s.fixed ? T_FIXED_STRUCT : T_STRUCT : value instanceof BMap m ? m.fixed ? T_FIXED_MAP : T_MAP :
            value instanceof Table ? T_TABLE : value instanceof Row ? T_ROW :
            value instanceof Column column ? column.owner instanceof Table ? T_COLUMN : T_TREE_COLUMN : value instanceof TypeDescription ? T_TYPE_DESCRIPTION :
            value instanceof Binary ? T_BINARY : value instanceof ErrorInfo ? T_ERROR_INFO :
            value instanceof SearchDirection ? T_SEARCH_DIRECTION : value instanceof SearchDirection.Namespace ? T_SEARCH_DIRECTIONS :
            value instanceof TypedValue nativeValue ? typeNamed(nativeValue.bslTypeName()) :
            value instanceof Module ? new Type(moduleTypeName(value.getClass())) : new Type(value.getClass().getSimpleName());
    }
    /** A module's type is its class name in the program, with the namespace: Справочники.Товар. */
    private static String moduleTypeName(Class<?> type) {
        BackgroundJobs application = Execution.currentApplication();
        String name = application == null ? null : application.nameOf(type);
        return name != null ? name : type.getName();
    }
    // Символы is a fixed system value: one immutable instance instead of a new structure per access.
    private static final Struct CHARS = (Struct) construct("ФиксированнаяСтруктура", new Object[]{"ПС,ВК,Таб", "\n", "\r", "\t"});
    private static final Struct JOB_STATES = (Struct) construct("ФиксированнаяСтруктура", new Object[]{
        "ОжидаетВыполнения,Выполняется,Завершено,ЗавершеноСОшибкой,Отменено", "ОжидаетВыполнения", "Выполняется", "Завершено", "ЗавершеноСОшибкой", "Отменено"});
    /** The compiler passes a lower-case name, so normalization does not allocate. */
    public static Object globalValue(String name) {
        Object http=HttpLibrary.global(name); if(http!=null) return http;
        Object json=JsonLibrary.global(name); if(json!=null) return json;
        if (name.equals("консоль") || name.equals("console")) return Execution.context().console();
        if (name.equals("модули") || name.equals("modules")) return Execution.context().modules();
        Object io = IoEnums.global(name);
        if (io != null) return io;
        if (name.equals("направлениесортировки") || name.equals("sortdirection")) return CollectionSupport.SortDirection.VALUES;
        if (name.equals("направлениепоиска") || name.equals("searchdirection")) return SearchDirection.VALUES;
        if (key(name).equals("символы")) return CHARS;
        if (name.equals("фоновыезадания") || name.equals("backgroundjobs")) return Execution.application();
        if (name.equals("аргументыкоманднойстроки") || name.equals("commandlinearguments")) {
            // A new array on every access: changing it does not change the program's arguments.
            BackgroundJobs application = Execution.currentApplication();
            BArray result = new BArray();
            if (application != null) result.values.addAll(application.arguments());
            return result;
        }
        if (name.equals("состояниефонового" + "задания") || name.equals("backgroundjobstate")) return JOB_STATES;
        throw new BslException("Unknown global value: " + name);
    }
    /** Names consumed by the compiler; type aliases share the runtime's type registry. */
    public static Object compilerTypes(Collection<String> modules) {
        BMap result = new BMap(); TYPES.keySet().forEach(name -> call(result,"Вставить",new Object[]{name,Boolean.TRUE}));
        for(String name:List.of("утверждения", "assertions", "временныйкаталог", "temporarydirectory", "парсер")) call(result,"Вставить",new Object[]{name,Boolean.TRUE});
        modules.forEach(name -> call(result,"Вставить",new Object[]{key(name),Boolean.TRUE})); return result;
    }
    public static Object compilerValues() {
        BMap result = new BMap();
        TYPES.keySet().forEach(name -> { if(IoEnums.global(name)!=null || JsonLibrary.global(name)!=null || HttpLibrary.global(name)!=null) call(result,"Вставить",new Object[]{name,Boolean.TRUE}); });
        for(String name:List.of("консоль", "console", "модули", "modules", "направлениесортировки", "sortdirection", "направлениепоиска", "searchdirection", "символы", "фоновыезадания", "backgroundjobs", "аргументыкоманднойстроки", "commandlinearguments", "состояниефонового" + "задания", "backgroundjobstate")) call(result,"Вставить",new Object[]{name,Boolean.TRUE});
        return result;
    }
    public static Object global(String name, Object[] args) {
        return globalNamed(key(name), args);
    }
    private static final class SingleCharacters {
        // BSL string offsets address UTF-16 code units, including isolated surrogates.
        // Lazy holder initialization publishes a bounded, immutable set safely.
        static final String[] VALUES = create();
        private static String[] create() {
            String[] values = new String[Character.MAX_VALUE + 1];
            for (int i = 0; i < values.length; i++) values[i] = String.valueOf((char) i);
            return values;
        }
    }
    private static String substring(String source, int start, int end) {
        return end - start == 1 ? SingleCharacters.VALUES[source.charAt(start)] : source.substring(start, end);
    }
    private static Object midValues(Object a, Object b, Object c, boolean hasLength) {
        String s = string(a);
        int start = Math.max(1, integer(b)) - 1;
        if (start >= s.length()) return "";
        int length = hasLength ? integer(c) : -1;
        if (length < 0 || length > s.length() - start) length = s.length() - start;
        return substring(s, start, start + length);
    }
    // Fixed-arity compiler entry points. Snapshot inputs in order before conversion,
    // as globalNamed does; the general dispatcher remains the ABI for old classes.
    public static Object mid(Object source, Object position) {
        Object a = read(source), b = read(position);
        return midValues(a, b, UNDEFINED, false);
    }
    public static Object mid(Object source, Object position, Object length) {
        Object a = read(source), b = read(position), c = read(length);
        return midValues(a, b, c, true);
    }
    public static Object stringLength(Object value) { return num(string(read(value)).length()); }
    public static Object left(Object source, Object length) {
        Object a = read(source), b = read(length);
        String s = string(a);
        return s.substring(0, Math.min(s.length(), Math.max(0, integer(b))));
    }
    public static Object right(Object source, Object length) {
        Object a = read(source), b = read(length);
        String s = string(a);
        return s.substring(Math.max(0, s.length() - Math.max(0, integer(b))));
    }
    // BSL whitespace includes NBSP.
    // Zero-width characters (U+200B, U+FEFF) remain part of the string.
    private static boolean trimSpace(char value) { return Character.isWhitespace(value) || value == '\u00a0'; }
    private static String trim(String text, boolean left, boolean right) {
        int start = 0, end = text.length();
        if (left) while (start < end && trimSpace(text.charAt(start))) start++;
        if (right) while (end > start && trimSpace(text.charAt(end - 1))) end--;
        return text.substring(start, end);
    }
    public static Object isBlankString(Object value) { return trim(string(read(value)), true, true).isEmpty(); }
    public static Object trimAll(Object value) { return trim(string(read(value)), true, true); }
    public static Object trimLeft(Object value) { return trim(string(read(value)), true, false); }
    public static Object trimRight(Object value) { return trim(string(read(value)), false, true); }
    public static Object lower(Object value) {
        String text=string(read(value));StringBuilder result=new StringBuilder(text.length());
        text.codePoints().forEach(c -> result.appendCodePoint(Character.toLowerCase(c)));return result.toString();
    }
    public static Object upper(Object value) {
        String text=string(read(value)); StringBuilder result=new StringBuilder(text.length());
        text.codePoints().forEach(c -> result.appendCodePoint(Character.toUpperCase(c))); return result.toString();
    }
    public static Object replace(Object source, Object search, Object replacement) {
        Object a = read(source), b = read(search), c = read(replacement);
        return string(b).isEmpty()?string(a):string(a).replace(string(b), string(c));
    }
    public static Object startsWith(Object source, Object prefix) {
        Object a = read(source), b = read(prefix);
        return string(a).startsWith(string(b));
    }
    public static Object strFind(Object source, Object search) {
        return strFind(source, search, Special.MISSING, Special.MISSING, Special.MISSING);
    }
    public static Object strFind(Object source, Object search, Object direction) {
        return strFind(source, search, direction, Special.MISSING, Special.MISSING);
    }
    public static Object strFind(Object source, Object search, Object direction, Object start) {
        return strFind(source, search, direction, start, Special.MISSING);
    }
    public static Object strFind(Object source, Object search, Object direction, Object start, Object occurrence) {
        Object a = read(source), b = read(search), c = read(direction), d = read(start), e = read(occurrence);
        return strFindValues(a, b, c, d, e);
    }
    private static boolean omitted(Object value) { return value == Special.MISSING || value == UNDEFINED; }
    private static Object strFindValues(Object source, Object search, Object direction, Object startValue, Object occurrenceValue) {
        String s = string(source), needle = string(search);
        if (!omitted(direction) && !(direction instanceof SearchDirection)) throw new BslException("Invalid StrFind direction");
        // OneScript returns 1 for an empty needle, even with zero occurrence.
        if (needle.isEmpty()) return num(1);
        boolean backwards = direction == SearchDirection.FROM_END;
        int start = omitted(startValue) ? 0 : integer(startValue);
        int occurrence = omitted(occurrenceValue) ? 1 : integer(occurrenceValue);
        if (start < 0 || start > s.length() || occurrence < 1) throw new BslException("Invalid StrFind arguments");
        if (start == 0) start = backwards ? s.length() : 1;
        // Backward start is the rightmost character allowed in a complete match.
        int cursor = backwards ? start - needle.length() : start - 1;
        int found = -1;
        for (int i = 0; i < occurrence; i++) {
            found = backwards ? s.lastIndexOf(needle, cursor) : s.indexOf(needle, cursor);
            if (found < 0) break;
            cursor = backwards ? found - needle.length() : found + 1;
        }
        return num(found + 1);
    }
    // Match globalNamed's argument reads before conversion; no argument array.
    public static Object toNumber(Object value) { return decimal(read(value)); }
    public static Object integerPart(Object value) { return decimal(read(value)).setScale(0, RoundingMode.DOWN); }
    public static Object roundNumber(Object value) { return decimal(read(value)).setScale(0, RoundingMode.HALF_UP); }
    public static Object roundNumber(Object value, Object places) {
        Object a = read(value), b = read(places);
        return decimal(a).setScale(integer(b), RoundingMode.HALF_UP);
    }
    private static RoundingMode rounding(Object value) {
        if(value instanceof IoEnums.Value)return IoEnums.code(value,IoEnums.ROUND).equals("Round15as10")?RoundingMode.HALF_DOWN:RoundingMode.HALF_UP;
        return integer(value)==0?RoundingMode.HALF_DOWN:RoundingMode.HALF_UP;
    }
    public static Object globalNamed(String name, Object[] args) {
        GlobalFunctions.Entry function = GlobalFunctions.find(name);
        if (function != null) return function.invoke(args);
        LegacyArity.check(name, args.length);
        Object a = args.length > 0 ? read(args[0]) : UNDEFINED;
        Object b = args.length > 1 ? read(args[1]) : UNDEFINED;
        Object c = args.length > 2 ? read(args[2]) : UNDEFINED;
        String s;
        return switch (name) {
            case "строка", "string" -> string(a);
            case "число", "number" -> decimal(a);
            case "тип", "type" -> typeNamed(string(a));
            case "типзнч", "typeof" -> typeOf(a);
            case "стрдлина", "strlen" -> num(string(a).length());
            case "сред", "mid" -> midValues(a, b, c, args.length >= 3);
            case "лев", "left" -> { s = string(a); yield s.substring(0, Math.min(s.length(), Math.max(0, integer(b)))); }
            case "прав", "right" -> { s = string(a); yield s.substring(Math.max(0, s.length() - Math.max(0, integer(b)))); }
            case "сокрлп", "trimall" -> trim(string(a), true, true);
            case "пустаястрока", "isblankstring" -> isBlankString(a);
            case "нрег", "lower" -> lower(a);
            case "врег", "upper" -> upper(a);
            case "стрзаменить", "strreplace" -> replace(a,b,c);
            case "стрначинаетсяс", "strstartswith" -> string(a).startsWith(string(b));
            case "стрнайти", "strfind" -> {
                yield strFindValues(a, b, c, args.length > 3 ? read(args[3]) : Special.MISSING,
                    args.length > 4 ? read(args[4]) : Special.MISSING);
            }
            case "стрразделить", "strsplit" -> {
                BArray result = new BArray(); String delimiters = string(b); s = string(a);
                int start = 0; boolean includeEmpty = args.length < 3 || truth(c);
                for (int i = 0; i <= s.length(); i++) if (i == s.length() || delimiters.indexOf(s.charAt(i)) >= 0) {
                    String part = s.substring(start, i); if (includeEmpty || !part.isEmpty()) result.values.add(part); start = i + 1;
                } yield result;
            }
            case "стрсоединить", "strconcat" -> {
                StringJoiner result = new StringJoiner(args.length > 1 ? string(b) : "");
                for (Object v : (BArray) a) result.add(string(v)); yield result.toString();
            }
            case "стршаблон", "strtemplate" -> {
                s=string(a); StringBuilder out=new StringBuilder();
                for(int i=0;i<s.length();i++) {
                    char ch=s.charAt(i); if(ch!='%'){out.append(ch);continue;}
                    if(++i==s.length())throw new BslException("Ошибка синтаксиса шаблона в позиции "+(i+1));
                    ch=s.charAt(i); if(ch=='%'){out.append('%');continue;}
                    if(ch<'1'||ch>'9')throw new BslException("Ошибка синтаксиса шаблона в позиции "+(i+1));
                    int index=ch-'0';
                    if(i+1<s.length()&&Character.isDigit(s.charAt(i+1))){index=index*10+s.charAt(++i)-'0';if(index>10)throw new BslException("Ошибка синтаксиса шаблона в позиции "+(i+1));}
                    if(index<args.length)out.append(string(read(args[index])));
                }
                yield out.toString();
            }
            case "мин", "min", "макс", "max" -> {
                Object best = a; boolean min = name.equals("мин") || name.equals("min");
                for (int i = 1; i < args.length; i++) if (min ? compare(best, args[i]) > 0 : compare(best, args[i]) < 0) best = read(args[i]); yield best;
            }
            case "цел", "int" -> decimal(a).setScale(0, RoundingMode.DOWN);
            case "окр", "round" -> decimal(a).setScale(args.length > 1 ? integer(b) : 0,
                args.length>2?rounding(c):RoundingMode.HALF_UP);
            case "символ", "char" -> { int code=integer(a); yield code<0||code>65535?"":String.valueOf((char)code); }
            case "кодсимвола", "charcode" -> { s=string(a); int index=args.length>1?integer(b)-1:0; yield num(index<0||index>=s.length()?-1:s.charAt(index)); }
            case "дата", "date" -> {
                if (args.length != 1 && args.length != 3 && args.length != 6) throw new BslException("Дата: ожидается 1, 3 или 6 аргументов, получено " + args.length);
                yield args.length == 1 ? date(string(a)) : BslDates.checked(LocalDateTime.of(integer(a), integer(b), integer(c), args.length > 3 ? integer(args[3]) : 0, args.length > 4 ? integer(args[4]) : 0, args.length > 5 ? integer(args[5]) : 0));
            }
            case "сообщить", "message" -> { Execution.message(string(a)); yield UNDEFINED; }
            case "завершитьработу", "exit" -> {
                int code=args.length==0?0:integer(a);
                if(code<0||code>255)throw new BslException("ЗавершитьРаботу: код выхода должен быть в диапазоне 0..255");
                throw new Exit(code);
            }
            case "проверитьотменуфонового" + "задания", "checkbackgroundcancellation" -> { Execution.checkCancellation(); yield UNDEFINED; }
            case "описаниеошибки", "errordescription" -> "";
            case "информацияобошибке", "errorinfo" -> NO_ERROR;
            case "подробноепредставлениеошибки", "detailerrordescription" -> detailDescription(errorInfoArgument(a));
            case "краткоепредставлениеошибки", "brieferrordescription" -> errorInfoArgument(a).description;
            case "текущаяуниверсальнаядатавмиллисекундах" -> num(System.currentTimeMillis());
            case "получитьдвоичныеданныеизhexстроки" -> new Binary(HexFormat.of().parseHex(string(a)));
            default -> throw new BslException("Unsupported global function: " + name);
        };
    }
}
