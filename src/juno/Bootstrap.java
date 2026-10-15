package juno;

import juno.runtime.RuntimeSupport;
import java.net.*;
import java.nio.file.*;

/**
 * Used by Build.java only. The compiler is written in BSL, so it is compiled by a previous
 * generation of itself: classes from a directory or JAR (bootstrap/seed.jar or a build generation).
 */
public final class Bootstrap {
    public static String parserErrors(Object errors, String source) {
        StringBuilder message = new StringBuilder("Parser errors: ").append(source);
        Object iterator = RuntimeSupport.iterator(errors);
        while (RuntimeSupport.hasNext(iterator)) {
            Object row = RuntimeSupport.next(iterator);
            message.append("\n").append(RuntimeSupport.string(RuntimeSupport.property(row, "НомерСтрокиНачала")))
                .append(":").append(RuntimeSupport.string(RuntimeSupport.property(row, "НомерКолонкиНачала")))
                .append(" ").append(RuntimeSupport.string(RuntimeSupport.property(row, "Текст")));
        }
        return message.toString();
    }

    /** compile &lt;classes&gt; &lt;output-dir&gt; (&lt;source.bsl&gt; &lt;ClassName&gt;)... */
    public static void main(String[] args) throws Exception {
        if (args.length < 5 || !args[0].equals("compile") || args.length % 2 != 1)
            throw new IllegalArgumentException("Bootstrap compile <classes> <output-dir> (<source> <class-name>)...");
        Path classes = Path.of(args[1]).toAbsolutePath();
        Path output = Path.of(args[2]);
        Files.createDirectories(output);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, Bootstrap.class.getClassLoader())) {
            for (int i = 3; i < args.length; i += 2) {
                // A fresh parser and compiler per module, as if each ran in its own process.
                Object parser = loader.loadClass("Parser").getConstructor().newInstance();
                Object compiler = loader.loadClass("Compiler").getConstructor().newInstance();
                Object ast = RuntimeSupport.call(parser, "Разобрать", new Object[]{Juno.read(Path.of(args[i]))});
                Object errors = RuntimeSupport.call(parser, "ТаблицаОшибок", new Object[]{});
                if (RuntimeSupport.decimal(RuntimeSupport.call(errors, "Количество", new Object[]{})).signum() != 0)
                    throw new IllegalArgumentException(parserErrors(errors, args[i]));
                RuntimeSupport.Binary data = (RuntimeSupport.Binary) RuntimeSupport.call(compiler, "Скомпилировать", new Object[]{parser, ast, args[i + 1]});
                Files.write(output.resolve(args[i + 1] + ".class"), data.bytes);
            }
        }
    }
}
