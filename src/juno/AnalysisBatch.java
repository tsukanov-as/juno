package juno;

import juno.runtime.WorkPool;
import juno.runtime.ApplicationContext;
import juno.runtime.Execution;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Ordered streaming output with at most 2*jobs files in flight. */
public final class AnalysisBatch implements Iterable<Juno.FileResult>, AutoCloseable {
    private final Juno sequential;
    private final WorkPool pool;
    private final List<Path> files;
    private final List<Juno.Plugin> checks;
    private final Juno.Dialect dialect;
    private final ThreadLocal<Juno> sessions;
    private final int window;
    private boolean iterated;
    private boolean closed;
    private final ApplicationContext template;
    private final Set<ApplicationContext> active = new HashSet<>();
    public AnalysisBatch(List<Path> files, List<Juno.Plugin> checks, int jobs, Consumer<String> messages) {
        this(files,checks,jobs,messages,Juno.Dialect.JUNO);
    }
    public AnalysisBatch(List<Path> files, List<Juno.Plugin> checks, int jobs, Consumer<String> messages, Juno.Dialect dialect) {
        if (jobs < 1 || jobs > 256) throw new IllegalArgumentException("--jobs must be 1..256");
        this.files = List.copyOf(files); this.checks = List.copyOf(checks);
        this.dialect = Objects.requireNonNull(dialect);
        sequential = jobs == 1 ? new Juno(messages) : null;
        pool = jobs == 1 ? null : new WorkPool(jobs, jobs);
        sessions = jobs == 1 ? null : ThreadLocal.withInitial(() -> new Juno(messages));
        window = jobs * 2;
        template = Execution.currentContext() == null ? null : Execution.context().snapshotWithoutStandardInput();
    }
    private Juno.FileResult analyze(Path path, Juno engine) {
        if (template == null) return (engine == null ? sessions.get() : engine).analyze(path, checks, dialect);
        ApplicationContext owner = template.snapshot();
        synchronized (this) {
            if (closed) { owner.close(); throw new IllegalStateException("AnalysisBatch is closed"); }
            active.add(owner);
        }
        try (owner; var scope = Execution.withContext(owner)) {
            return (engine == null ? sessions.get() : engine).analyze(path, checks, dialect);
        } finally { synchronized (this) { active.remove(owner); } }
    }
    public Iterator<Juno.FileResult> iterator() {
        if (iterated) throw new IllegalStateException("AnalysisBatch can only be consumed once");
        iterated = true;
        return new Iterator<>() {
            private int next, submitted;
            private final ArrayDeque<WorkPool.Task<Juno.FileResult>> pending = new ArrayDeque<>();
            public boolean hasNext() { return next < files.size(); }
            public Juno.FileResult next() {
                if (!hasNext()) throw new NoSuchElementException();
                if (sequential != null) return analyze(files.get(next++), sequential);
                while (submitted < files.size() && pending.size() < window) {
                    Path path = files.get(submitted++);
                    pending.addLast(pool.submit(() -> analyze(path, null)));
                }
                Juno.FileResult result = pending.removeFirst().join(); next++; return result;
            }
        };
    }
    public void close() {
        List<ApplicationContext> owners;
        synchronized (this) { if (closed) return; closed = true; owners = List.copyOf(active); }
        try { ApplicationContext.cancelAll(owners); }
        finally { try { if (pool != null) pool.close(); } finally { if (template != null) template.close(); } }
    }
}
