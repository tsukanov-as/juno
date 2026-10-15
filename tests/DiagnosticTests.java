import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import juno.*;
import static juno.runtime.RuntimeSupport.*;

/** User-facing diagnostics agree in handlers, assertions, CLI and standalone programs. */
public final class DiagnosticTests {
    static void check(boolean ok,Object why){if(!ok)throw new AssertionError(why);}
    static String run(Path source){ByteArrayOutputStream out=new ByteArrayOutputStream(),err=new ByteArrayOutputStream();int code=Main.execute(new String[]{"run",source.toString()},new PrintStream(out),new PrintStream(err));check(code!=0,"expected failure");return err.toString(StandardCharsets.UTF_8);}
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("juno-diagnostics-");
        try {
            Path source=root.resolve("Program.bsl");
            Files.writeString(source,"А=Неопределено; А.Метод();");
            String text=run(source);check(text.contains("Значение не является значением объектного типа")&&!text.contains("Module ABI"),text);
            Files.writeString(source,"Функция Делить()\nВозврат 1/0;\nКонецФункции\nСообщить(Делить());");
            text=run(source);check(text.contains("Деление на 0")&&!text.contains("Division"),text);check(text.contains("Program.bsl:4")&&text.contains("Program.bsl:2"),text);
            Juno compiler=new Juno();
            for(String code:List.of("Если Ложь Тогда Обработатт(1); КонецЕсли;", "Х = Новый Масив;", "Х = Опечатка;", "Сред(,1);")) {
                try { compiler.compile(code,"typo.bsl","Typo"); throw new AssertionError(code); }
                catch(Juno.Failure e){check(e.diagnostics.getFirst().line()==1 && e.diagnostics.getFirst().column()>0,e.diagnostics);check(!e.getMessage().contains("Compiler.bsl"),e.getMessage());}
            }
            for(String expression:List.of("Цел()","Цел(1.5, 2)","Строка()","Строка(1, 2)","Число()","Число(\"1\", 2)","Тип()","Тип(\"Число\", 1)","ТипЗнч()","ТипЗнч(1, 2)","СтрДлина()","СтрДлина(\"abc\", \"x\")","Сред(\"abc\")","Сред(\"abc\", 1, 1, 1)","Лев(\"abc\")","Лев(\"abc\", 1, 9)","Прав(\"abc\")","Прав(\"abc\", 1, 9)","СокрЛП()","СокрЛП(\"a\", \"b\")","ПустаяСтрока()","ПустаяСтрока(\"a\", \"b\")","НРег(\"a\", \"b\")","ВРег(\"a\", 1)","СтрЗаменить(\"a\", \"a\")","СтрЗаменить(\"a\", \"a\", \"b\", \"c\")","СтрНачинаетсяС(\"a\")","СтрНайти(\"a\")","СтрНайти(\"a\", \"a\", 1, 1, 1, 1)","СтрРазделить(\"a\")","СтрРазделить(\"a\", \"b\", Истина, 1)","СтрСоединить()","СтрСоединить(Новый Массив, \"\", 1)","СтрШаблон()","СтрШаблон(\"%1\", 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)","Мин()","Макс()","Окр()","Окр(2.5, 0, 0, 7, 8)","Символ()","Символ(65, 1)","КодСимвола()","КодСимвола(\"a\", 1, 2)","Дата()","Дата(2024, 1)","Дата(2024, 1, 1, 1)","Дата(2024, 1, 1, 1, 1)","Дата(2024, 1, 1, 1, 1, 1, 1)","Сообщить()","Сообщить(\"a\", \"b\", \"c\")","ОписаниеОшибки(1)","ИнформацияОбОшибке(1)","ПодробноеПредставлениеОшибки()","КраткоеПредставлениеОшибки()","Окр(2.5, 0, 0, 7, 8)","Макс()")) {
                try { compiler.compile(expression+";", "arity.bsl", "Arity"); throw new AssertionError(expression); }
                catch(Juno.Failure expected) {check(expected.getMessage().contains("число аргументов"), expected.getMessage());}
            }
            Files.writeString(source,"Если Ложь Тогда Обработатт(1); КонецЕсли;");
            ByteArrayOutputStream lint=new ByteArrayOutputStream();
            check(Main.execute(new String[]{"lint",source.toString()},new PrintStream(lint),System.err)!=0,lint);
            check(lint.toString(StandardCharsets.UTF_8).contains("Обработатт"),lint);
            Files.writeString(source,"А=1;\nБ=А \\ 2;");
            text=run(source);check(text.contains("Program.bsl:2:5")&&!text.contains("Parser.bsl"),text);
            Files.write(source,new byte[]{(byte)0xff});
            text=run(source);check(text.contains("Program.bsl:1:1")&&text.contains("UTF-8")&&!text.contains("Input length"),text);
            Files.write(source,new byte[]{'A','=', '1',';', '\n','B','=',(byte)0xff});
            text=run(source);check(text.contains("Program.bsl:2:3")&&text.contains("байт 8"),text);
            Files.writeString(source,"И = 1;");text=run(source);check(text.contains("Ожидался оператор")&&!text.contains("КонецТекста"),text);
            System.out.println("Diagnostics PASS");
        }finally{try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
