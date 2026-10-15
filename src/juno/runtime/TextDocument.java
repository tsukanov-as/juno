package juno.runtime;

import java.util.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Editable in-memory text. Large files should use TextReader and streams instead. */
final class TextDocument implements NativeObject, TypedValue {
    private final List<String> lines=new ArrayList<>();
    private boolean trailing;
    private void setText(String text){lines.clear();lines.addAll(split(text));trailing=text.endsWith("\n")||text.endsWith("\r");}
    public String bslTypeName() { return "ТекстовыйДокумент"; }
    public Object property(String name) { return missingProperty(bslTypeName(),name); }
    private static List<String> split(String text) {
        if(text.isEmpty()) return List.of();
        String normalized=text.replace("\r\n","\n").replace('\r','\n');
        List<String> result=new ArrayList<>(Arrays.asList(normalized.split("\n",-1)));
        if(normalized.endsWith("\n")) result.removeLast();
        return result;
    }
    private int index(Object value,boolean insertion) {
        int index=integer(value)-1;
        if(index<0 || index>lines.size() || (!insertion && index==lines.size())) throw error("Номер строки документа вне диапазона");
        return index;
    }
    public Object invoke(String name,Object[] args) {
        args=values(args);
        return switch(name) {
            case "прочитать", "read" -> {
                arity(name,args,1,2); try(TextIo.Input input=new TextIo.Input(args)) {
                    Object text=input.read(UNDEFINED); setText(text==UNDEFINED ? "" : string(text));
                } yield UNDEFINED;
            }
            case "записать", "write" -> {
                arity(name,args,1,2); try(TextIo.Output output=new TextIo.Output(args)) { for(String line:lines) output.write(line,"\r\n"); } yield UNDEFINED;
            }
            case "установитьтекст", "settext" -> { arity(name,args,1,1); setText(string(args[0])); yield UNDEFINED; }
            case "получитьтекст", "gettext" -> { arity(name,args,0,0); yield lines.isEmpty() ? "" : String.join("\n",lines)+(trailing?"\n":""); }
            case "количествострок", "linecount" -> { arity(name,args,0,0); yield number(Integer.toString(lines.size())); }
            case "получитьстроку", "getline" -> { arity(name,args,1,1); yield lines.get(index(args[0],false)); }
            case "добавитьстроку", "addline" -> { arity(name,args,1,1); List<String> add=split(string(args[0])); lines.addAll(add.isEmpty() ? List.of("") : add); trailing=true; yield UNDEFINED; }
            case "вставитьстроку", "insertline" -> { arity(name,args,2,2); List<String> add=split(string(args[1])); lines.addAll(index(args[0],true),add.isEmpty() ? List.of("") : add); trailing=true; yield UNDEFINED; }
            case "заменитьстроку", "replaceline" -> {
                arity(name,args,2,2); int index=index(args[0],false); List<String> add=split(string(args[1])); lines.remove(index); lines.addAll(index,add.isEmpty() ? List.of("") : add); yield UNDEFINED;
            }
            case "удалитьстроку", "deleteline" -> { arity(name,args,1,1); lines.remove(index(args[0],false)); trailing=!lines.isEmpty(); yield UNDEFINED; }
            case "очистить", "clear" -> { arity(name,args,0,0); lines.clear(); trailing=false; yield UNDEFINED; }
            default -> missingMethod(bslTypeName(),name);
        };
    }
}
