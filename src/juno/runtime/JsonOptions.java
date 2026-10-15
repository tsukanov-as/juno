package juno.runtime;

import java.math.*;
import java.util.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Immutable, explicit JSON settings; unsupported switches fail before a destination opens. */
final class JsonOptions implements NativeObject, TypedValue {
    static final IoEnums.Namespace VALUE_TYPE=new IoEnums.Namespace("ТипЗначенияJSON",new String[][]{
        {"Ничего","None"},{"Null","Null"},{"Булево","Boolean"},{"ИмяСвойства","PropertyName"},{"Комментарий","Comment"},
        {"КонецМассива","ArrayEnd"},{"КонецОбъекта","ObjectEnd"},{"НачалоМассива","ArrayStart"},{"НачалоОбъекта","ObjectStart"},{"Строка","String"},{"Число","Number"}});
    static final IoEnums.Namespace LINES=new IoEnums.Namespace("ПереносСтрокJSON",new String[][]{{"Авто","Auto"},{"Unix","Unix"},{"Windows","Windows"},{"Нет","None"}});
    static final IoEnums.Namespace ESCAPE=new IoEnums.Namespace("ЭкранированиеСимволовJSON",new String[][]{{"Нет","None"},{"СимволыВнеASCII","NotASCIISymbols"},{"СимволыВнеBMP","SymbolsNotInBMP"}});
    static final IoEnums.Namespace DATES=new IoEnums.Namespace("ФорматДатыJSON",new String[][]{{"ISO","ISO"},{"JavaScript","JavaScript"},{"Microsoft","Microsoft"}});
    static final IoEnums.Namespace DATE_VARIANT=new IoEnums.Namespace("ВариантЗаписиДатыJSON",new String[][]{{"ЛокальнаяДата","LocalDate"},{"ЛокальнаяДатаСоСмещением","LocalDateWithOffset"},{"УниверсальнаяДата","UniversalDate"}});
    final Object lines,escape;
    final String padding,newline;
    final boolean angles,lineTerminators,ampersand,singleQuotes,slash;
    JsonOptions(Object[] args) {
        arity("ПараметрыЗаписиJSON",args,0,9);
        lines=optional(args,0,LINES.property("авто")); String code=IoEnums.code(lines,LINES);
        newline=switch(code) { case "None" -> ""; case "Unix" -> "\n"; case "Windows" -> "\r\n"; default -> System.lineSeparator(); };
        Object indent=optional(args,1,""); if(!(indent instanceof String text) || !text.matches("[ \\t]*") || text.length()>256) throw error("Отступ JSON должен содержать до 256 пробелов/табуляций"); padding=text;
        if(!bool(optional(args,2,true))) throw error("JSON поддерживает только двойные кавычки");
        escape=optional(args,3,ESCAPE.property("нет")); IoEnums.code(escape,ESCAPE);
        angles=bool(optional(args,4,false)); lineTerminators=bool(optional(args,5,true)); ampersand=bool(optional(args,6,false)); singleQuotes=bool(optional(args,7,false)); slash=bool(optional(args,8,false));
    }
    static JsonOptions value(Object value) { if(omitted(value)) return new JsonOptions(new Object[0]); if(value instanceof JsonOptions settings) return settings; throw error("Ожидались ПараметрыЗаписиJSON"); }
    static boolean bool(Object value) { if(value instanceof Boolean b) return b; throw error("Параметр JSON должен иметь тип Булево"); }
    static TextEncoding encoding(Object value) {
        if(!omitted(value) && !(value instanceof String)) throw error("JSON encoding: ожидается имя кодировки строкой");
        return TextEncoding.value(omitted(value) || value.equals("") ? "UTF-8" : value);
    }
    public String bslTypeName() { return "ПараметрыЗаписиJSON"; }
    public String toString() { return bslTypeName(); }
    public Object property(String name) {
        return switch(name) {
            case "переносстрок","newlines" -> lines;
            case "символыотступа","paddingsymbols" -> padding;
            case "использоватьдвойныекавычки","usedoublequotes" -> true;
            case "экранированиесимволов","escapecharacters" -> escape;
            case "экранироватьугловыескобки","escapeanglebrackets" -> angles;
            case "экранироватьразделителистрок","escapelineterminators" -> lineTerminators;
            case "экранироватьамперсанд","escapeampersand" -> ampersand;
            case "экранироватьодинарныекавычки","escapesinglequotes" -> singleQuotes;
            case "экранироватьслеш","escapeslash" -> slash;
            default -> missingProperty(bslTypeName(),name);
        };
    }
    public Object invoke(String name,Object[] args) { return missingMethod(bslTypeName(),name); }
    static void validString(String text) {
        if(text.length()>JsonTokenizer.MAX_TOKEN) throw error("Строка JSON превышает "+JsonTokenizer.MAX_TOKEN+" символов");
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(Character.isHighSurrogate(c)) { if(++i>=text.length() || !Character.isLowSurrogate(text.charAt(i))) throw error("JSON: непарный surrogate"); }
            else if(Character.isLowSurrogate(c)) throw error("JSON: непарный surrogate");
        }
    }
    String quote(String text) {
        validString(text); String mode=IoEnums.code(escape,ESCAPE); StringBuilder result=new StringBuilder("\"");
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            switch(c) {
                case '"' -> result.append("\\\""); case '\\' -> result.append("\\\\");
                case '/' -> result.append(slash ? "\\/" : "/");
                case '\b' -> result.append("\\b"); case '\f' -> result.append("\\f"); case '\n' -> result.append("\\n"); case '\r' -> result.append("\\r"); case '\t' -> result.append("\\t");
                default -> {
                    boolean escaped=c<32 || (angles && (c=='<' || c=='>')) || (lineTerminators && (c==0x2028 || c==0x2029))
                        || (ampersand && c=='&') || (singleQuotes && c=='\'') || (mode.equals("NotASCIISymbols") && c>127) || (mode.equals("SymbolsNotInBMP") && Character.isSurrogate(c));
                    if(escaped) { result.append("\\u"); for(int n=12;n>=0;n-=4) result.append("0123456789abcdef".charAt((c>>n)&15)); }
                    else result.append(c);
                }
            }
        }
        return result.append('"').toString();
    }
    static String number(BigDecimal value) {
        value=value.stripTrailingZeros();
        if(Math.abs((long)value.scale())>100_000 || (long)value.precision()+Math.max(0L,-(long)value.scale())>JsonTokenizer.MAX_TOKEN) throw error("Число JSON превышает безопасный диапазон вывода");
        return value.toPlainString();
    }
}
