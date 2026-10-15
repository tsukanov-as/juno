package juno.runtime;

import java.io.*;
import java.nio.file.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Explicit owned workspace for tests; no process cwd mutation and no followed symlinks. */
final class TemporaryDirectory implements NativeObject, TypedValue, ApplicationContext.Resource {
    final ApplicationContext owner = Execution.context();
    private final Path path;
    private boolean closed;

    TemporaryDirectory(Object[] args) {
        arity("ВременныйКаталог", args, 0, 1);
        String prefix = string(optional(args, 0, "juno-test-"));
        if (!prefix.matches("[\\p{L}\\p{N}_-]{3,64}")) throw error("TemporaryDirectory prefix:3..64 letters/digits/_/-");
        try { path = Files.createTempDirectory(prefix); }
        catch (IOException e) { throw failure("Создание test workspace", e); }
        owner.own(this);
    }
    public String bslTypeName() { return "ВременныйКаталог"; }
    public String toString() { return bslTypeName(); }
    public Object property(String name) {
        owner.requireOpen();
        if (closed) throw error("TemporaryDirectory closed");
        return switch (name) {
            case "путь", "path" -> path.toString();
            default -> missingProperty(bslTypeName(), name);
        };
    }
    public Object invoke(String name, Object[] args) {
        args = values(args);
        if (name.equals("закрыть") || name.equals("close")) {
            arity(name, args, 0, 0);
            close();
            return UNDEFINED;
        }
        return missingMethod(bslTypeName(), name);
    }
    public void close() {
        if (closed) return;
        closed = true;
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                public FileVisitResult visitFile(Path p, java.nio.file.attribute.BasicFileAttributes a) throws IOException {
                    Files.deleteIfExists(p);
                    return FileVisitResult.CONTINUE;
                }
                public FileVisitResult postVisitDirectory(Path p, IOException e) throws IOException {
                    if (e != null) throw e;
                    Files.deleteIfExists(p);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) { throw failure("Удаление test workspace", e); }
        finally { owner.release(this); }
    }
    public void cancel() { close(); }
}
