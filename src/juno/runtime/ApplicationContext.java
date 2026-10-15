package juno.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static juno.runtime.RuntimeSupport.*;

/** Per-application/job environment and resource ownership; never changes JVM process state. */
public final class ApplicationContext implements AutoCloseable {
    public interface Resource extends AutoCloseable {
        @Override void close();
        /** Release the blocking handle without waiting for a reader/writer's operation lock. */
        void cancel();
    }
    private Path directory;
    private final Map<String, String> environment;
    private final InputStream input;
    private final OutputStream output, error;
    private final ConsoleIo console;
    private final boolean standardInputAllowed;
    private final List<Resource> resources = new ArrayList<>();
    private boolean closed, cancelled;
    private ApplicationProgram program=ApplicationProgram.EMPTY;
    private ModuleRegistry modules;
    public ApplicationContext(Path directory, Map<String, String> environment, InputStream input, OutputStream output, OutputStream error) {
        this(directory, environment, new ConsoleIo.Input(Objects.requireNonNull(input)), output, error, null, true);
    }
    private ApplicationContext(Path directory, Map<String, String> environment, ConsoleIo.Input input, OutputStream output, OutputStream error, ConsoleIo previous, boolean standardInputAllowed) {
        this.directory = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(this.directory)) throw new BslException("Рабочий каталог не существует: " + directory);
        this.environment = System.getProperty("os.name").startsWith("Windows") ? new TreeMap<>(String.CASE_INSENSITIVE_ORDER) : new LinkedHashMap<>();
        this.environment.putAll(environment);
        this.input = input.stream; this.output = Objects.requireNonNull(output); this.error = Objects.requireNonNull(error);
        this.console = new ConsoleIo(this, input, previous);
        this.standardInputAllowed = standardInputAllowed;
    }
    public static ApplicationContext system() {
        return new ApplicationContext(Path.of(""), System.getenv(), System.in, System.out, System.err);
    }
    public synchronized ApplicationContext snapshot() {
        requireOpen(); ApplicationContext copy=new ApplicationContext(directory, environment, console.input, output, error, console, standardInputAllowed);copy.program=program;return copy;
    }
    /** Worker/lint scopes cannot block on a host-owned handle that cancellation must not close. */
    public synchronized ApplicationContext snapshotWithoutStandardInput() {
        requireOpen(); ApplicationContext copy=new ApplicationContext(directory, environment, console.input, output, error, console, false);copy.program=program;return copy;
    }
    boolean standardInputAllowed() { requireOpen(); return standardInputAllowed; }
    public synchronized Path directory() { requireOpen(); return directory; }
    public synchronized void directory(Path path) {
        requireOpen(); Path target = resolve(path);
        if (!Files.isDirectory(target)) throw new BslException("Рабочий каталог не существует: " + target);
        directory = target;
    }
    public synchronized Path resolve(Path path) { requireOpen(); return (path.isAbsolute() ? path : directory.resolve(path)).normalize().toAbsolutePath(); }
    public synchronized Map<String, String> environment() { requireOpen(); return Map.copyOf(environment); }
    public synchronized String environment(String name) { requireOpen(); return environment.get(name); }
    public synchronized void environment(String name, String value) {
        requireOpen(); if (value == null) environment.remove(name); else environment.put(name, value);
    }
    public synchronized ApplicationProgram program(){requireOpen();return program;}
    public synchronized void program(ApplicationProgram program){requireOpen();this.program=Objects.requireNonNull(program);}
    public InputStream input() { return input; }
    public OutputStream output() { return output; }
    public OutputStream error() { return error; }
    public ConsoleIo console() { requireOpen(); return console; }
    synchronized ModuleRegistry modules() {
        requireOpen();
        BackgroundJobs application = Execution.currentApplication();
        if (application == null) throw new BslException("Модули требуют контекста приложения juno");
        if (modules == null) modules = new ModuleRegistry(this, application);
        return modules;
    }
    public <T extends Resource> T own(T resource) {
        synchronized (this) { if (!closed) { resources.add(resource); return resource; } }
        // Registration racing with cancellation must not leak the newly opened handle.
        BslException failure = new BslException("Контекст ресурсов закрыт");
        try { resource.cancel(); } catch (RuntimeException | Error e) { failure.addSuppressed(e); }
        throw failure;
    }
    public synchronized void release(Resource resource) { resources.remove(resource); }
    public synchronized int openResourceCount() { return resources.size(); }
    synchronized void requireOpen() { if (closed) throw new BslException("Контекст приложения закрыт"); }
    private void finish(boolean cancel) {
        List<Resource> pending;
        synchronized (this) {
            if (cancel ? cancelled : closed) return;
            if (cancel) cancelled = true;
            closed = true; pending = new ArrayList<>(resources); modules = null;
        }
        Collections.reverse(pending); Throwable failure = null;
        for (Resource resource : pending) try { if (cancel) resource.cancel(); else resource.close(); }
        catch (RuntimeException | Error e) { if (failure == null) failure = e; else if (failure != e) failure.addSuppressed(e); }
        finally { release(resource); }
        if (failure instanceof RuntimeException e) throw e;
        if (failure instanceof Error e) throw e;
    }
    /** Does not close host stdin/stdout/stderr. */
    @Override public void close() { finish(false); }
    public void cancel() { finish(true); }
    public static void cancelAll(Collection<ApplicationContext> owners) {
        Throwable failure = null;
        for (ApplicationContext owner : owners) try { owner.cancel(); }
        catch (RuntimeException | Error e) { if (failure == null) failure = e; else if (failure != e) failure.addSuppressed(e); }
        if (failure instanceof RuntimeException e) throw e;
        if (failure instanceof Error e) throw e;
    }
}
