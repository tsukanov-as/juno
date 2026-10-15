import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import juno.Main;

/** The actual launchers, where JVM stack sizing, closed pipes and cwd matter. */
public final class LauncherTests {
    static final String JAVA=Path.of(System.getProperty("java.home"),"bin","java").toString();
    static final String JUNO=Path.of("build/juno.jar").toAbsolutePath().toString();
    static void check(boolean ok,Object why){if(!ok)throw new AssertionError(why);}
    static void run(Path cwd,List<String> command,String expected) throws Exception {
        Path out=cwd.resolve("out.txt"),err=cwd.resolve("err.txt");
        Process process=new ProcessBuilder(command).directory(cwd.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        if(!process.waitFor(20,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor();throw new AssertionError("launcher timeout");}
        check(process.exitValue()==0,Files.readString(err));check(Files.readString(out).contains(expected),Files.readString(out));
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("juno-launchers-");
        try {
            Path source=root.resolve("Recursion.bsl"),built=root.resolve("recursion.jar");
            Files.writeString(source,"Функция Глубина(Н) Если Н=0 Тогда Возврат 0; КонецЕсли; Возврат Глубина(Н-1)+1; КонецФункции Сообщить(Глубина(20000));");
            run(root,List.of(JAVA,"-jar",JUNO,"run",source.toString()),"20000");
            run(root,List.of(JAVA,"-jar",JUNO,"build",source.toString(),"--output",built.toString()),"Готово");
            run(root,List.of(JAVA,"-jar",built.toString()),"20000");
            Files.writeString(source,"Для Н=1 По 100000 Цикл Сообщить(Н); КонецЦикла;");
            Path errors=root.resolve("pipe-errors.txt");
            Process pipe=new ProcessBuilder(JAVA,"-jar",JUNO,"run",source.toString()).redirectError(errors.toFile()).start();
            try(InputStream output=pipe.getInputStream()){check(output.read()!=-1,"first output byte");}
            if(!pipe.waitFor(10,TimeUnit.SECONDS)){pipe.destroyForcibly();pipe.waitFor();throw new AssertionError("closed output timeout");}
            check(pipe.exitValue()==0&&Files.size(errors)==0,Files.readString(errors));
            Path dot=Files.createDirectory(root.resolve("dot-input"));Files.writeString(dot.resolve("Программа.bsl"),"");Files.writeString(dot.resolve("juno.json"),"{\"schemaVersion\":1,\"entry\":\"Программа.bsl\",\"sources\":[\".\"]}");
            run(dot,List.of(JAVA,"-jar",JUNO,"build","."),"dot-input.jar");
            check(Files.isRegularFile(dot.resolve("dot-input.jar"))&&!Files.exists(dot.resolve("..jar")),"normalized directory JAR name");
            Files.writeString(source,"ЗавершитьРаботу(300);");
            ByteArrayOutputStream err=new ByteArrayOutputStream();int code=Main.execute(new String[]{"run",source.toString()},new PrintStream(OutputStream.nullOutputStream()),new PrintStream(err));
            check(code==3&&err.toString(StandardCharsets.UTF_8).contains("0..255"),err);
            System.out.println("Launchers PASS");
        }finally{try(var files=Files.walk(root)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
