package juno;

import juno.runtime.RuntimeSupport;
import juno.runtime.Execution;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;

/** Stateful, single-threaded session: compiles BSL and runs plugins. The CLI is built on top of it. */
public final class Juno {
    /** Keep the default JVM launcher stack from limiting ordinary recursive BSL programs. */
    public static void launch(Runnable action) {
        java.util.concurrent.FutureTask<Void> task=new java.util.concurrent.FutureTask<>(action,null);
        Thread worker=Thread.ofPlatform().name("juno-main").stackSize(64L*1024*1024).start(task);
        try{task.get();}
        catch(InterruptedException e){worker.interrupt();Thread.currentThread().interrupt();throw new IllegalStateException("Запуск программы прерван",e);}
        catch(java.util.concurrent.ExecutionException e){Throwable cause=e.getCause();if(cause instanceof RuntimeException r)throw r;if(cause instanceof Error r)throw r;throw new IllegalStateException(cause);}
    }
    public enum Dialect { PLATFORM, JUNO }
    public static final String VERSION = "dev";
    public static final int SCHEMA_VERSION = 1;
    private Object parser;
    private final Object compiler;
    private final MemoryLoader plugins = new MemoryLoader();
    private int pluginNumber;
    private Collection<String> moduleNames = List.of();
    public void moduleNames(Collection<String> names) { moduleNames = List.copyOf(names); }
    private final Consumer<String> messages;
    public Juno() { this(Execution.messageSink()); }
    public Juno(Consumer<String> messages) {
        this.messages = Objects.requireNonNull(messages);
        try (var scope = Execution.messages(messages)) { parser = instance("Parser"); compiler = instance("Compiler"); }
    }

    public record Diagnostic(String file, String phase, String source, String code, String message,
                             int line, int column, int endLine, int endColumn, int start, int end) {
        public Map<String,Object> json() {
            Map<String,Object> r = new LinkedHashMap<>();
            r.put("file", file); r.put("phase", phase); r.put("source", source); r.put("code", code);
            r.put("message", message); r.put("severity", "error");
            r.put("line", line); r.put("column", column); r.put("endLine", endLine); r.put("endColumn", endColumn);
            r.put("start", start); r.put("end", end); return r;
        }
    }
    public static final class Failure extends RuntimeException {
        public final List<Diagnostic> diagnostics;
        public final List<String> bslStack;
        public Failure(List<Diagnostic> diagnostics, Throwable cause) {
            this(diagnostics, cause, List.of());
        }
        public Failure(List<Diagnostic> diagnostics, Throwable cause, List<String> stack) {
            super(diagnostics.get(0).message(), cause); this.diagnostics = List.copyOf(diagnostics); this.bslStack = List.copyOf(stack);
        }
    }
    public record Plugin(String name, Class<?> implementation, Object parameters) {}
    public record FileResult(String file, String sha256, List<Diagnostic> diagnostics,
                             Map<String,Object> pluginResults, boolean failed) {
        public Map<String,Object> json() {
            Map<String,Object> r = new LinkedHashMap<>();
            r.put("type", "file"); r.put("schemaVersion", SCHEMA_VERSION); r.put("file", file); r.put("sha256", sha256);
            r.put("status", failed ? "failed" : diagnostics.isEmpty() ? "ok" : "findings");
            r.put("diagnostics", diagnostics.stream().map(Diagnostic::json).toList());
            r.put("pluginResults", pluginResults); return r;
        }
    }
    public static Object instance(String name) {
        try { return instantiate(Class.forName(name)); }
        catch (ClassNotFoundException e) { throw new IllegalStateException("Missing compiled " + name + "; rebuild the juno JAR", e); }
    }
    private static Object instantiate(Class<?> cls) {
        try { return cls.getConstructor().newInstance(); }
        catch (ReflectiveOperationException e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException i ? i.getCause() : e;
            throw cause instanceof RuntimeException r ? r : new IllegalStateException(cause);
        }
    }
    private static Object call(Object receiver, String name, Object... args) { return RuntimeSupport.call(receiver, name, args); }
    private static Object property(Object receiver, String name) { return RuntimeSupport.getPropertyNamed(receiver, name.toLowerCase(Locale.ROOT)); }
    public static String read(Path file) throws IOException {return read(Files.readAllBytes(file),file);}
    private static String read(byte[] bytes,Path file) {
        java.nio.ByteBuffer input=java.nio.ByteBuffer.wrap(bytes);
        java.nio.CharBuffer chars=java.nio.CharBuffer.allocate(bytes.length);
        java.nio.charset.CoderResult result=StandardCharsets.UTF_8.newDecoder().decode(input,chars,true);
        chars.flip();String text=normalize(chars.toString());
        if(result.isError()) {
            int line=1,column=1;
            for(int i=0;i<text.length();i++){if(text.charAt(i)=='\n'){line++;column=1;}else column++;}
            throw new Failure(List.of(new Diagnostic(file.toString(),"read","juno","encoding",
                "Исходный файл содержит недопустимую последовательность UTF-8 (байт "+(input.position()+1)+")",line,column,line,column,text.length()+1,text.length()+1)),null);
        }
        return text;
    }
    public static String normalize(String source) {
        if (source.startsWith("\uFEFF")) source = source.substring(1);
        return source.replace("\r\n", "\n").replace('\r', '\n');
    }
    public static String className(String name) {
        for (String part : name.split("\\.", -1)) {
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.codePointAt(0))) throw new IllegalArgumentException("Invalid class name: " + name);
            if (!part.codePoints().allMatch(Character::isJavaIdentifierPart)) throw new IllegalArgumentException("Invalid class name: " + name);
        }
        if (Set.of("Parser","Compiler").contains(name) || name.startsWith("java.") || name.startsWith("juno.") && !name.startsWith("juno.plugins."))
            throw new IllegalArgumentException("Reserved class package: " + name);
        return name;
    }
    private void clear() {
        try { call(parser, "Очистить"); }
        catch (RuntimeException e) { parser = instance("Parser"); }
    }
    private Object parse(String source, String file, Dialect dialect) {
        clear();
        RuntimeSupport.assign(RuntimeSupport.property(parser, "РасширенияJuno"), dialect == Dialect.JUNO);
        try { return call(parser, "Разобрать", normalize(source)); }
        catch (RuntimeException e) {
            List<Diagnostic> diagnostics = diagnostics(file, "parse");
            if (diagnostics.isEmpty()) diagnostics.add(problem(file, "parse", e));
            parser = instance("Parser"); throw new Failure(diagnostics, e);
        }
    }
    private List<Diagnostic> diagnostics(String file, String phase) {
        List<Diagnostic> result = new ArrayList<>();
        for (Object row : items(call(parser, "ТаблицаОшибок"))) {
            result.add(new Diagnostic(file, phase, RuntimeSupport.string(property(row,"источник")),
                RuntimeSupport.string(optional(row,"код")), RuntimeSupport.string(property(row,"текст")),
                number(row,"номерстрокиначала"), number(row,"номерколонкиначала"),
                number(row,"номерстрокиконца"), number(row,"номерколонкиконца"),
                number(row,"позицияначала"), number(row,"позицияконца")));
        }
        return result;
    }
    private static Object optional(Object object, String name) {
        try { return property(object, name); } catch (RuntimeSupport.BslException e) { return RuntimeSupport.undefined(); }
    }
    private static int number(Object object, String name) {
        Object value = optional(object,name); return value instanceof BigDecimal n ? n.intValue() : 0;
    }
    public static Diagnostic problem(String file, String phase, Throwable e) {
        StackTraceElement frame=sourceFrame(e);
        String message=message(e);
        if(frame!=null && phase.equals("runtime"))message+=" ("+location(e)+")";
        return new Diagnostic(file, phase, "juno", e.getClass().getSimpleName(),
            message, 0,0,0,0,0,0);
    }
    private static StackTraceElement sourceFrame(Throwable error) {
        for(Throwable e=error;e!=null;e=e.getCause())for(StackTraceElement frame:e.getStackTrace()) {
            String file=frame.getFileName();
            if(RuntimeSupport.isBslSourceFile(file) && frame.getLineNumber()>0)return frame;
        }
        return null;
    }
    /** The text of an error for people; endless recursion gets a plain explanation instead of a JVM name. */
    public static String message(Throwable error) {
        if (error instanceof StackOverflowError) return "слишком глубокая рекурсия: метод или Новый вызывают себя без конца";
        return RuntimeSupport.description(error);
    }
    public static String location(Throwable error) {
        StackTraceElement frame=sourceFrame(error);return frame==null?"":frame.getFileName()+":"+frame.getLineNumber();
    }
    public byte[] compile(String source, String file, String name) { return compile(source,file,name,null); }
    /** defines: preprocessor symbols of the target environment; null rejects conditional preprocessor instructions. */
    public byte[] compile(String source, String file, String name, List<String> defines) {
        try (var scope = Execution.messages(messages)) { return compileInScope(source, file, name, defines); }
    }
    private byte[] compileInScope(String source, String file, String name, List<String> defines) {
        className(name); UnsupportedSyntax.check(normalize(source),file); Object ast = parse(source,file,Dialect.JUNO);
        List<Diagnostic> errors = diagnostics(file,"parse");
        if (!errors.isEmpty()) { clear(); throw new Failure(errors,null); }
        try {
            RuntimeSupport.assign(RuntimeSupport.property(compiler,"СигнатурыГлобальныхФункций"),juno.runtime.GlobalFunctions.compilerSignatures());
            List<String> types = new ArrayList<>(moduleNames); types.add(name);
            RuntimeSupport.assign(RuntimeSupport.property(compiler,"ДоступныеТипы"),RuntimeSupport.compilerTypes(types));
            RuntimeSupport.assign(RuntimeSupport.property(compiler,"ГлобальныеЗначения"),RuntimeSupport.compilerValues());
            RuntimeSupport.assign(RuntimeSupport.property(compiler,"ИмяИсходника"),Path.of(file).getFileName().toString());
            RuntimeSupport.assign(RuntimeSupport.property(compiler,"ОкружениеКомпиляции"),defines==null?RuntimeSupport.undefined():String.join(",",defines));
            return ((RuntimeSupport.Binary)call(compiler,"Скомпилировать",parser,ast,name)).bytes;
        } catch (RuntimeException e) {
            Object token = optional(compiler,"токенкомпиляции");
            Diagnostic d = problem(file,"compile",e);
            if (token != RuntimeSupport.undefined()) d = new Diagnostic(file,"compile","juno",d.code(),d.message(),
                number(token,"номерстроки"),number(token,"номерколонки"),number(token,"номерстроки"),number(token,"номерколонки"),number(token,"позиция"),number(token,"позиция"));
            throw new Failure(List.of(d),e);
        } finally { clear(); }
    }
    public Path compile(Path source, String name, Path directory) throws IOException { return compile(source,name,directory,null); }
    public Path compile(Path source, String name, Path directory, List<String> defines) throws IOException {
        Path output = directory.resolve(className(name).replace('.','/') + ".class");
        byte[] bytes = compile(read(source),source.toString(),name,defines);
        Files.createDirectories(output.toAbsolutePath().getParent()); Files.write(output,bytes); return output;
    }
    public Plugin plugin(Path path, Object parameters) throws IOException {
        String name = path.getFileName().toString();
        String className = "juno.plugins.Check" + pluginNumber++;
        Class<?> implementation = plugins.define(className, compile(read(path),path.toString(),className));
        return new Plugin(name,implementation,parameters);
    }
    public FileResult analyze(Path file, List<Plugin> checks) { return analyze(file,checks,Dialect.JUNO); }
    public FileResult analyze(Path file, List<Plugin> checks, Dialect dialect) {
        try (var scope = Execution.messages(messages)) { return analyzeInScope(file, checks, Objects.requireNonNull(dialect)); }
    }
    private static Collection<String> analysisModuleNames(Path file) throws IOException {
        Path manifest=App.projectFile(file);
        if(manifest!=null)try{Json.object(Json.parse(Files.readString(manifest,StandardCharsets.UTF_8)));}
        catch(IllegalArgumentException | java.nio.charset.CharacterCodingException malformed) {
            // --config/--plugin may explicitly replace a broken lint configuration. The CLI
            // validates its chosen configuration; file analysis can still use adjacent modules.
            List<String> names=new ArrayList<>();Path root=App.root(file);
            for(Path candidate:files(root)) {
                names.add(root.relativize(candidate).toString().replace(java.io.File.separatorChar,'.').replaceFirst("(?i)\\.bsl$",""));
                names.add(candidate.getFileName().toString().replaceFirst("(?i)\\.bsl$",""));
            }
            return names;
        }
        return new Project(file,true,null).modules.keySet();
    }
    private FileResult analyzeInScope(Path file, List<Plugin> checks, Dialect dialect) {
        List<Diagnostic> errors = new ArrayList<>(); Map<String,Object> results = new LinkedHashMap<>();
        String sha = ""; boolean failed = false;
        List<Object> instances = new ArrayList<>(); List<Integer> opened = new ArrayList<>();
        Object ast = null;
        try {
            byte[] bytes = Files.readAllBytes(file); sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            String text = read(bytes,file);
            if (dialect == Dialect.JUNO) {
                moduleNames(analysisModuleNames(file));
                compile(text, file.toString(), "juno.plugins.Analysis");
            }
            ast = parse(text,file.toString(),dialect);
            errors.addAll(diagnostics(file.toString(),"parse"));
            if (!errors.isEmpty()) return new FileResult(file.toString(),sha,errors,results,true);
            if (checks.isEmpty()) return new FileResult(file.toString(),sha,List.of(),Map.of(),false);
            for (Plugin check : checks) instances.add(instantiate(check.implementation()));
            call(parser,"Подключить",fromJson(instances));
            Object parameters = RuntimeSupport.construct("Соответствие",new Object[0]);
            for (int i=0;i<checks.size();i++) call(parameters,"Вставить",instances.get(i),fromJson(checks.get(i).parameters()));
            // Parser owns Open/callbacks. Close is called once after visit, or best effort after failure.
            call(parser,"Посетить",ast,parameters);
            for (int i=0;i<instances.size();i++) opened.add(i);
        } catch (Failure e) { errors.addAll(e.diagnostics); failed = true; }
        catch (Exception e) {
            errors.add(problem(file.toString(),"analysis",e)); failed = true;
            // Some plugins may have opened before failure; Close is best effort on all constructed instances.
            for (int i=0;i<instances.size();i++) opened.add(i);
        } finally {
            for (int i : opened) try {
                results.put(checks.get(i).name(),toJson(call(instances.get(i),"Закрыть")));
            } catch (RuntimeException e) { errors.add(problem(file.toString(),"plugin-close:"+checks.get(i).name(),e)); failed=true; }
            if (ast != null) {
                List<Diagnostic> all = diagnostics(file.toString(),"analysis");
                // There were no parser diagnostics when the visitor ran.
                if (errors.stream().noneMatch(d -> d.phase().equals("parse"))) errors.addAll(all);
            }
            if(failed)parser=instance("Parser");
            else {if(!instances.isEmpty())call(parser,"Подключить",fromJson(List.of()));clear();}
        }
        return new FileResult(file.toString(),sha,List.copyOf(errors),results,failed);
    }
    /** A .bsl file, or all of them under a directory (sorted, .git skipped). */
    public static List<Path> files(Path input) throws IOException {
        if (!Files.exists(input)) throw new IOException("Path does not exist: " + input);
        if (Files.isRegularFile(input)) return List.of(input.toAbsolutePath().normalize());
        try (var walk = Files.walk(input)) {
            return walk.filter(Files::isRegularFile).filter(p -> {
                for (Path part : input.relativize(p)) if (part.toString().equals(".git")) return false;
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return name.endsWith(".bsl");
            }).map(p -> p.toAbsolutePath().normalize()).sorted().toList();
        }
    }
    public static List<Object> items(Object collection) {
        List<Object> result=new ArrayList<>(); Object it=RuntimeSupport.iterator(collection);
        while(RuntimeSupport.hasNext(it)) result.add(RuntimeSupport.next(it)); return result;
    }
    public static Object fromJson(Object value) {
        if(value==null) return RuntimeSupport.nullValue();
        if(value instanceof List<?> list) {
            Object array=RuntimeSupport.construct("Массив",new Object[0]);
            for(Object item:list) call(array,"Добавить",fromJson(item)); return array;
        }
        if(value instanceof Map<?,?> map) {
            Object struct=RuntimeSupport.construct("Структура",new Object[0]);
            for(var e:map.entrySet()) call(struct,"Вставить",e.getKey().toString(),fromJson(e.getValue())); return struct;
        }
        return value;
    }
    public static Object toJson(Object value) { return toJson(value,new IdentityHashMap<>(),0); }
    private static Object toJson(Object value,IdentityHashMap<Object,Boolean> stack,int depth) {
        value=RuntimeSupport.read(value);
        if(value==RuntimeSupport.undefined()) return Map.of("$juno","undefined");
        if(value==RuntimeSupport.nullValue()) return Map.of("$juno","null");
        if(value==null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if(depth>100 || stack.put(value,true)!=null) throw new IllegalArgumentException("Cyclic or deeply nested plugin result");
        try {
            if(value instanceof RuntimeSupport.Struct) {
                Map<String,Object> map=new LinkedHashMap<>();
                for(Object item:items(value)) map.put(RuntimeSupport.string(property(item,"ключ")),toJson(property(item,"значение"),stack,depth+1)); return map;
            }
            if(value instanceof RuntimeSupport.BArray) {
                List<Object> list=new ArrayList<>();for(Object item:items(value)) list.add(toJson(item,stack,depth+1));return list;
            }
            if(value instanceof RuntimeSupport.Table) {
                List<Object> rows=new ArrayList<>(); List<Object> columns=items(property(value,"колонки"));
                for(Object row:items(value)) { Map<String,Object> map=new LinkedHashMap<>();
                    for(Object column:columns) { String name=RuntimeSupport.string(property(column,"имя"));map.put(name,toJson(RuntimeSupport.getIndex(row,name),stack,depth+1)); }
                    rows.add(map);
                } return rows;
            }
            if(value instanceof RuntimeSupport.BMap) {
                List<Object> entries=new ArrayList<>();
                for(Object item:items(value)) {Map<String,Object> entry=new LinkedHashMap<>();entry.put("key",toJson(property(item,"ключ"),stack,depth+1));entry.put("value",toJson(property(item,"значение"),stack,depth+1));entries.add(entry);}
                return Map.of("$juno","map","entries",entries);
            }
            if(value instanceof java.time.LocalDateTime) return Map.of("$juno","date","value",value.toString());
            if(value instanceof RuntimeSupport.Binary data) return Map.of("$juno","binary","base64",Base64.getEncoder().encodeToString(data.bytes));
            throw new IllegalArgumentException("Unsupported plugin result type: "+value.getClass().getName());
        } finally { stack.remove(value); }
    }
    private static final class MemoryLoader extends ClassLoader {
        MemoryLoader() { super(Juno.class.getClassLoader()); }
        Class<?> define(String name,byte[] bytes) { return defineClass(name,bytes,0,bytes.length); }
    }
}
