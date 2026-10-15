package juno.runtime;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded submissions; ForkJoinTask.join can execute children with one worker. */
public final class WorkPool implements AutoCloseable {
    private final ForkJoinPool pool;
    private final Semaphore capacity;
    private final Set<Task<?>> tasks = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public WorkPool(int workers, int queued) {
        if (workers < 1 || workers > 256 || queued < 0 || queued > 65536)
            throw new IllegalArgumentException("workers must be 1..256 and queue 0..65536");
        capacity = new Semaphore(workers + queued);
        pool = new ForkJoinPool(workers);
    }
    public final class Task<T> extends RecursiveTask<T> {
        private final Callable<T> body;
        private final AtomicInteger phase = new AtomicInteger(); // queued, running, released
        Task(Callable<T> body) { this.body = body; }
        protected T compute() {
            if (!phase.compareAndSet(0, 1)) return null;
            try { return body.call(); }
            catch (RuntimeException | Error e) { throw e; }
            catch (Exception e) { throw new CompletionException(e); }
            finally { phase.set(2); release(); }
        }
        private void release() { tasks.remove(this); capacity.release(); }
        public boolean cancelQueued() {
            if (!phase.compareAndSet(0, 2)) return false;
            super.cancel(false); release(); return true;
        }
    }
    public <T> Task<T> submit(Callable<T> body) {
        if (closed || !capacity.tryAcquire()) throw new RejectedExecutionException("Worker queue is full or closed");
        Task<T> task = new Task<>(body); tasks.add(task);
        try {
            if (closed) throw new RejectedExecutionException("Worker pool is closed");
            if (Thread.currentThread() instanceof ForkJoinWorkerThread t && t.getPool() == pool) task.fork();
            else pool.execute(task);
            return task;
        } catch (RuntimeException e) { task.cancelQueued(); throw e; }
    }
    public void close() {
        closed = true;
        for (Task<?> task : tasks) task.cancelQueued();
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Workers did not stop; blocking native code must cooperate with cancellation");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted closing workers", e); }
    }
}
