package juno.runtime;

import java.util.function.Consumer;

/** Task services and message routing, scoped to host entry points, not BSL calls. */
public final class Execution {
    private Execution() {}
    private record Context(BackgroundJobs application, BackgroundJobs.Job job, Consumer<String> messages, ApplicationContext io) {}
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    public static final class Scope implements AutoCloseable {
        private final Context previous;
        private boolean closed;
        private Scope(Context context) { previous = CURRENT.get(); CURRENT.set(context); }
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            if (previous != null && previous.job != null && previous.job.cancellationRequested())
                Thread.currentThread().interrupt();
        }
    }
    public static Scope messages(Consumer<String> sink) {
        Context c = CURRENT.get();
        return new Scope(new Context(c == null ? null : c.application, c == null ? null : c.job, sink, c == null ? null : c.io));
    }
    static Scope enter(BackgroundJobs app, BackgroundJobs.Job job, Consumer<String> sink, ApplicationContext io) {
        return new Scope(new Context(app, job, sink, io));
    }
    /** Explicit Java-embedding scope; the host closes the supplied owner with try-with-resources. */
    public static Scope withContext(ApplicationContext context) {
        Context c = CURRENT.get();
        return new Scope(new Context(c == null ? null : c.application, c == null ? null : c.job,
            c == null ? System.out::println : c.messages, context));
    }
    public static ApplicationContext currentContext() { Context c = CURRENT.get(); return c == null ? null : c.io; }
    public static ApplicationContext context() {
        ApplicationContext context = currentContext();
        if (context == null) throw new RuntimeSupport.BslException("Файловый API требует контекст приложения; Java host: Execution.withContext(ApplicationContext)");
        return context;
    }
    public static Consumer<String> messageSink() {
        Context c = CURRENT.get(); return c == null ? System.out::println : c.messages;
    }
    public static void message(String text) { messageSink().accept(text); }
    static BackgroundJobs application() {
        Context c = CURRENT.get();
        if (c == null || c.application == null) throw new RuntimeSupport.BslException("ФоновыеЗадания требуют контекста juno run/build или BackgroundJobs Java API");
        return c.application;
    }
    static BackgroundJobs currentApplication() {
        Context c = CURRENT.get(); return c == null ? null : c.application;
    }
    static BackgroundJobs.Job job() { Context c = CURRENT.get(); return c == null ? null : c.job; }
    /** Fast common path: no task-context lookup until the thread is interrupted. */
    public static void checkCancellation() {
        if (!Thread.currentThread().isInterrupted()) return;
        BackgroundJobs.Job job = job();
        if (job == null || job.cancellationRequested()) throw new Cancelled();
    }
    public static final class Cancelled extends Error {
        public Cancelled() { super("Выполнение отменено", null, false, false); }
    }
}
