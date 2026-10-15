package juno.runtime;

import static juno.runtime.RuntimeSupport.*;

/** String functions for BSL values. Positions remain UTF-16 based. */
final class BslStrings {
    private BslStrings() {}
    static boolean identifier(String text) {
        if(text.isEmpty())return false;int first=text.codePointAt(0);if(first!='_'&&!Character.isLetter(first))return false;
        for(int i=Character.charCount(first);i<text.length();){int c=text.codePointAt(i);if(c!='_'&&!Character.isLetterOrDigit(c))return false;i+=Character.charCount(c);}return true;
    }
    static Object occurrences(Object source,Object needle) {
        String text=string(source), part=string(needle);int count=0;
        if(!part.isEmpty())for(int pos=0;(pos=text.indexOf(part,pos))>=0;pos+=part.length())count++;
        return number(Integer.toString(count));
    }
    static Object title(Object source) {
        String text=string(source);StringBuilder result=new StringBuilder(text.length());boolean initial=true;
        for(int i=0;i<text.length();){int c=text.codePointAt(i);i+=Character.charCount(c);
            result.appendCodePoint(initial?Character.toUpperCase(c):Character.toLowerCase(c));initial=!Character.isLetterOrDigit(c);
        }return result.toString();
    }
    static Object localized(Object[] args) {
        String text=string(args[0]);String language=IoSupport.omitted(IoSupport.optional(args,1,UNDEFINED))?"ru":string(args[1]);
        java.util.regex.Pattern pattern=java.util.regex.Pattern.compile("([A-Za-z0-9_-]+)\\s*=\\s*(?:'((?:''|[^'])*)'|\"((?:\"\"|[^\"])*)\"|([^;]*))\\s*(?:;|$)");
        java.util.regex.Matcher matcher=pattern.matcher(text);
        while(matcher.find())if(matcher.group(1).equalsIgnoreCase(language)) {
            if(matcher.group(2)!=null)return matcher.group(2).replace("''", "'");
            if(matcher.group(3)!=null)return matcher.group(3).replace("\"\"", "\"");
            return matcher.group(4).strip();
        }
        return "";
    }
    static Object compare(Object a, Object b) {
        return number(Integer.toString(Integer.signum(StringOrder.compareIgnoringCase(string(a), string(b)))));
    }
    static Object endsWith(Object value, Object suffix) {
        String end = string(suffix);
        if (end.isEmpty()) throw new BslException("СтрЗаканчиваетсяНа: второй аргумент не может быть пустым");
        return string(value).endsWith(end);
    }
    static Object lineCount(Object value) {
        String text = string(value);
        return number(Long.toString(1 + text.chars().filter(c -> c == '\n').count()));
    }
    static Object getLine(Object value, Object number) {
        int index = decimal(number).toBigInteger().intValueExact();
        if (index < 1) return "";
        String[] lines = string(value).split("\n", -1);
        if (index > lines.length) throw new BslException("СтрПолучитьСтроку: номер строки вне диапазона");
        return lines[index - 1];
    }
}
