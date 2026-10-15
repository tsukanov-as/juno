package juno.runtime;

import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import static juno.runtime.RuntimeSupport.*;

/** Incremental grammar/cursor for public JSON. Comments are tokens; JSON values stay strict. */
final class JsonTokenizer {
    static final int MAX_DEPTH=256, MAX_TOKEN=1_048_576;
    enum Kind { None, Null, Boolean, PropertyName, Comment, ArrayEnd, ObjectEnd, ArrayStart, ObjectStart, String, Number }
    private static final class Frame {
        final boolean object;
        int state; // 0 initial/key-or-value; 1 after comma (no end); 2 object value; 3 after value
        Frame(boolean object) { this.object=object; }
    }
    private final Reader input;
    private final ArrayDeque<Frame> frames=new ArrayDeque<>();
    private int look=-2;
    private long line,position;
    private long tokenLine,tokenPosition;
    private boolean previousCr, started, rootDone, failed;
    boolean strictComments;
    Kind kind=Kind.None;
    Object value=UNDEFINED;
    JsonTokenizer(Reader input) { this.input=input; }
    long line() { return tokenLine; }
    long position() { return tokenPosition; }
    int depth() { return frames.size(); }
    boolean rootComplete() { return rootDone && frames.isEmpty(); }
    BslException invalid(String message) { return new BslException("JSON: "+message+" (строка "+line+", позиция "+position+")"); }
    private int peek() throws IOException { if(look==-2) look=input.read(); return look; }
    private int take() throws IOException {
        int c=peek(); look=-2; if(c<0) return c;
        if(line==0) line=1;
        if(c=='\r') { line++; position=0; previousCr=true; }
        else if(c=='\n') { if(!previousCr) line++; position=0; previousCr=false; }
        else { position++; previousCr=false; }
        return c;
    }
    private void whitespace() throws IOException { while(peek()==' ' || peek()=='\t' || peek()=='\r' || peek()=='\n') take(); }
    private String comment() throws IOException {
        if(strictComments)throw invalid("Недопустимое состояние потока чтения JSON: комментарий внутри значения");
        take(); int form=take(); StringBuilder text=new StringBuilder();
        if(form=='/') {
            while(peek()!=-1 && peek()!='\r' && peek()!='\n') append(text,take());
        } else if(form=='*') {
            while(true) {
                int c=take(); if(c<0) throw invalid("Незавершенный комментарий");
                if(c=='*' && peek()=='/') { take(); break; } append(text,c);
            }
        } else throw invalid("Ожидался комментарий // или /* */");
        return text.toString();
    }
    private void append(StringBuilder text,int c) {
        if(text.length()>=MAX_TOKEN) throw invalid("Токен превышает "+MAX_TOKEN+" символов"); text.append((char)c);
    }
    private void colon() throws IOException {
        whitespace(); while(peek()=='/') { comment(); whitespace(); }
        if(take()!=':') throw invalid("Ожидалось ':' после имени свойства");
    }
    boolean next() {
        if(failed) throw invalid("Чтение после ошибки; установите новый источник");
        try { boolean result=advance(); tokenLine=line; tokenPosition=position; return result; }
        catch(IOException e) { failed=true; tokenLine=line; tokenPosition=position; throw IoSupport.failure("JSON (строка "+line+", позиция "+position+")",e); }
        catch(RuntimeException | Error e) { failed=true; tokenLine=line; tokenPosition=position; throw e; }
    }
    private boolean advance() throws IOException {
        Execution.checkCancellation();
        while(true) {
            whitespace(); int c=peek();
            if(c=='/') { kind=Kind.Comment; value=comment(); return true; }
            Frame frame=frames.peek();
            if(frame==null && started) {
                if(c>=0) throw invalid("Данные после корневого значения");
                kind=Kind.None; value=UNDEFINED; return false;
            }
            if(c<0) {
                if(frame!=null) throw invalid("Неожиданный конец JSON");
                kind=Kind.None; value=UNDEFINED; return false;
            }
            if(frame!=null) {
                if(frame.state==3) {
                    if(c==(frame.object ? '}' : ']')) return end(frame);
                    if(take()!=',') throw invalid("Ожидалась запятая или конец контейнера");
                    frame.state=1; continue;
                }
                if(frame.state==0 && c==(frame.object ? '}' : ']')) return end(frame);
                if(frame.object && frame.state!=2) {
                    if(c!='"') throw invalid("Ожидалось имя свойства в двойных кавычках");
                    String name=text(); colon(); frame.state=2; kind=Kind.PropertyName; value=name; return true;
                }
                frame.state=3;
            } else { started=true; }
            value=UNDEFINED;
            switch(c) {
                case '{', '[' -> {
                    if(frames.size()>=MAX_DEPTH) throw invalid("Превышена глубина "+MAX_DEPTH);
                    take(); frames.push(new Frame(c=='{')); kind=c=='{' ? Kind.ObjectStart : Kind.ArrayStart;
                }
                case '"' -> { kind=Kind.String; value=text(); }
                case 't' -> { literal("true"); kind=Kind.Boolean; value=true; }
                case 'f' -> { literal("false"); kind=Kind.Boolean; value=false; }
                case 'n' -> { literal("null"); kind=Kind.Null; value=Special.NULL; }
                default -> {
                    if(c!='-' && (c<'0' || c>'9')) throw invalid("Ожидалось значение JSON");
                    kind=Kind.Number; value=numeric();
                }
            }
            if(frames.isEmpty()) rootDone=true;
            return true;
        }
    }
    private boolean end(Frame frame) throws IOException {
        take(); frames.pop(); kind=frame.object ? Kind.ObjectEnd : Kind.ArrayEnd; value=UNDEFINED;
        if(frames.isEmpty()) rootDone=true; return true;
    }
    private void literal(String text) throws IOException {
        for(int i=0;i<text.length();i++) if(take()!=text.charAt(i)) throw invalid("Недопустимое значение");
        boundary();
    }
    private void boundary() throws IOException {
        int c=peek(); if(c!=-1 && c!=' ' && c!='\t' && c!='\r' && c!='\n' && c!=',' && c!=']' && c!='}' && c!='/') throw invalid("Недопустимый символ после значения");
    }
    private BigDecimal numeric() throws IOException {
        StringBuilder number=new StringBuilder();
        if(peek()=='-') append(number,take());
        if(peek()=='0') { append(number,take()); if(digit(peek())) throw invalid("Ведущий ноль в числе"); }
        else { if(!digit(peek())) throw invalid("Ожидалась цифра"); while(digit(peek())) append(number,take()); }
        if(peek()=='.') { append(number,take()); if(!digit(peek())) throw invalid("Ожидалась дробная часть"); while(digit(peek())) append(number,take()); }
        if(peek()=='e' || peek()=='E') {
            append(number,take()); if(peek()=='+' || peek()=='-') append(number,take());
            if(!digit(peek())) throw invalid("Ожидалась экспонента"); while(digit(peek())) append(number,take());
        }
        boundary();
        try {
            BigDecimal value=new BigDecimal(number.toString());
            if(Math.abs((long)value.scale())>100_000) throw invalid("Экспонента числа вне безопасного диапазона ±100000");
            return value;
        } catch(NumberFormatException e) { throw invalid("Недопустимое число"); }
    }
    private static boolean digit(int c) { return c>='0' && c<='9'; }
    private int unicode() throws IOException {
        int n=0;
        for(int i=0;i<4;i++) { int c=take(); int hex= c>='0' && c<='9' ? c-'0' : c>='a' && c<='f' ? c-'a'+10 : c>='A' && c<='F' ? c-'A'+10 : -1;
            if(hex<0) throw invalid("Недопустимый Unicode escape"); n=(n<<4)|hex; }
        return n;
    }
    private String text() throws IOException {
        take(); StringBuilder text=new StringBuilder(); boolean high=false;
        while(true) {
            int c=take(); if(c<0) throw invalid("Незавершенная строка");
            if(c=='"') { if(high) throw invalid("Непарный surrogate"); return text.toString(); }
            if(c<32) throw invalid("Неэкранированный управляющий символ");
            if(c=='\\') c=switch(take()) {
                case '"' -> '"'; case '\\' -> '\\'; case '/' -> '/'; case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                case 'u' -> unicode(); default -> throw invalid("Недопустимый escape");
            };
            if(high) { if(!Character.isLowSurrogate((char)c)) throw invalid("Непарный surrogate"); high=false; }
            else if(Character.isLowSurrogate((char)c)) throw invalid("Непарный surrogate");
            else high=Character.isHighSurrogate((char)c);
            append(text,c);
        }
    }
    boolean skip() {
        if(kind==Kind.ObjectStart || kind==Kind.ArrayStart) {
            int current=depth(); while(next() && depth()>=current) { }
        }
        return next();
    }
    void validateRootTail() {
        if(!rootDone || depth()!=0) return;
        Kind previous=kind; Object previousValue=value; long previousLine=tokenLine,previousPosition=tokenPosition;
        while(next()) { if(kind!=Kind.Comment) throw invalid("Данные после корневого значения"); }
        kind=previous; value=previousValue; tokenLine=previousLine; tokenPosition=previousPosition;
    }
}
