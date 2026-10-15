package juno.runtime;

import java.lang.invoke.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static juno.runtime.RuntimeSupport.*;
import juno.runtime.RuntimeSupport.Module;

/** In-process background jobs, with fresh module graphs and snapshot transport. */
public final class BackgroundJobs implements RuntimeSupport.NativeObject, AutoCloseable {
    private record Factory(String name, MethodHandle constructor) {
        Module create() {
            try { return (Module) constructor.invokeExact(); }
            catch (RuntimeException | Error e) { throw e; }
            catch (Throwable e) { throw new BslException("Cannot instantiate " + name + ": " + e); }
        }
    }
    private record Target(String module, String method) {}
    private record Key(Target target, Object value) {}
    private final Map<String, Factory> factories = new LinkedHashMap<>();
    private final Map<Class<?>, String> names = new HashMap<>(); // class → name in the program, for ТипЗнч
    private volatile List<String> arguments = List.of();         // АргументыКоманднойСтроки
    private final Map<String, Map<String, String>> bindings;
    private final boolean explicitInstances;
    private final WorkPool pool;
    private final Consumer<String> messages;
    private final ApplicationContext context;
    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
    private final Map<Key, Job> keys = new HashMap<>();
    private volatile Map<String, Module> root;
    private boolean closed, started;
    private static final int HISTORY = 256;
    private static final int MESSAGE_LIMIT = 1000;

    public BackgroundJobs(Map<String, Class<?>> modules, Map<String, Map<String, String>> bindings,
                          int workers, int queued, Consumer<String> messages) {
        this(modules, bindings, workers, queued, messages, false, inheritedContext());
    }
    /** Source applications instantiate only their entry class; BSL New creates dependencies. */
    public BackgroundJobs(Map<String, Class<?>> modules, int workers, int queued, Consumer<String> messages) {
        this(modules, Map.of(), workers, queued, messages, true, inheritedContext());
    }
    public BackgroundJobs(Map<String, Class<?>> modules, int workers, int queued, Consumer<String> messages, ApplicationContext context) {
        this(modules, Map.of(), workers, queued, messages, true, context);
    }
    public BackgroundJobs(Map<String, Class<?>> modules, Map<String, Map<String, String>> bindings,
                          int workers, int queued, Consumer<String> messages, ApplicationContext context) {
        this(modules, bindings, workers, queued, messages, false, context);
    }
    private static ApplicationContext inheritedContext() {
        ApplicationContext current = Execution.currentContext(); return current == null ? ApplicationContext.system() : current.snapshot();
    }
    private BackgroundJobs(Map<String, Class<?>> modules, Map<String, Map<String, String>> bindings,
                           int workers, int queued, Consumer<String> messages, boolean explicitInstances, ApplicationContext context) {
        this.context = Objects.requireNonNull(context);
        this.explicitInstances = explicitInstances;
        Map<String, Map<String, String>> copiedBindings = new LinkedHashMap<>();
        bindings.forEach((owner, fields) -> copiedBindings.put(owner, Map.copyOf(fields)));
        this.bindings = Map.copyOf(copiedBindings); this.messages = Objects.requireNonNull(messages);
        for (var item : modules.entrySet()) {
            Class<?> type = item.getValue();
            if (!Module.class.isAssignableFrom(type)) throw new IllegalArgumentException("Not a BSL module: " + item.getKey());
            try {
                MethodHandle constructor = MethodHandles.publicLookup().findConstructor(type, MethodType.methodType(void.class))
                    .asType(MethodType.methodType(Module.class));
                if (factories.putIfAbsent(normalize(item.getKey()), new Factory(item.getKey(), constructor)) != null)
                    throw new IllegalArgumentException("Duplicate module: " + item.getKey());
                names.put(type, item.getKey());
            } catch (ReflectiveOperationException e) { throw new IllegalArgumentException("Public module constructor required: " + item.getKey(), e); }
        }
        pool = new WorkPool(workers, queued);
    }
    private static String normalize(String value) { return value.toLowerCase(Locale.ROOT); }
    /** Command-line arguments of the program: АргументыКоманднойСтроки. */
    public void setArguments(List<String> arguments) { this.arguments = List.copyOf(arguments); }
    List<String> arguments() { return arguments; }
    /** The name of a module class in this program: Справочники.Товар; null for a foreign class. */
    String nameOf(Class<?> type) { return names.get(type); }
    /** Source modules only; the built-in parser is a compiler service. No module is instantiated here. */
    List<String> moduleNames() {
        return names.entrySet().stream().filter(e -> !e.getKey().getName().equals("Parser"))
            .map(Map.Entry::getValue).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
    /**
     * Новый Модуль(Параметры): the module body runs first, then ПриСозданииОбъекта(Параметры),
     * if the module has it (exported or not). Missing arguments take the default values.
     */
    public Module newModule(String name, Object[] arguments) {
        Factory factory = factories.get(normalize(name));
        if (factory == null) throw new BslException("Неизвестный класс проекта: " + name);
        Module module = factory.create();
        Method constructor = CONSTRUCTORS.get(module.getClass()).orElse(null);
        if (constructor == null) {
            if (arguments.length != 0)
                throw new BslException("Класс " + name + " не принимает параметры: в модуле нет процедуры ПриСозданииОбъекта");
            return module;
        }
        int count = constructor.getParameterCount();
        if (arguments.length > count)
            throw new BslException("Слишком много параметров для Новый " + name + ": ПриСозданииОбъекта принимает " + count);
        int required = requiredConstructorArguments(module);
        if (arguments.length < required)
            throw new BslException("Не хватает параметров для Новый " + name + ": ПриСозданииОбъекта требует " + required);
        Object[] values = Arrays.copyOf(arguments, count);
        for (int i = arguments.length; i < count; i++) values[i] = missing();
        try { constructor.invoke(module, values); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            if (e.getCause() instanceof Error r) throw r;
            throw new BslException(String.valueOf(e.getCause()));
        } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        return module;
    }
    /** From the module metadata: C, parameter count, required count. */
    private static int requiredConstructorArguments(Module module) {
        for (String line : module.__juno_metadata().split("\n")) {
            String[] parts = line.split("\t", -1);
            if (parts[0].equals("C") && parts.length == 3) return Integer.parseInt(parts[2]);
        }
        return 0;
    }
    private static final ClassValue<Optional<Method>> CONSTRUCTORS = new ClassValue<>() {
        @Override protected Optional<Method> computeValue(Class<?> type) {
            for (Method method : type.getDeclaredMethods()) {
                String name = method.getName();
                if (name.equalsIgnoreCase("ПриСозданииОбъекта") || name.equalsIgnoreCase("OnObjectCreate")) {
                    method.setAccessible(true);
                    return Optional.of(method);
                }
            }
            return Optional.empty();
        }
    };
    private Map<String, Module> modules() {
        Map<String, Module> result = new LinkedHashMap<>();
        for (var item : factories.entrySet()) result.put(item.getKey(), item.getValue().create());
        for (var owner : bindings.entrySet()) {
            Module module = result.get(normalize(owner.getKey()));
            if (module == null) throw new IllegalArgumentException("Unknown binding owner: " + owner.getKey());
            for (var field : owner.getValue().entrySet()) {
                Module dependency = result.get(normalize(field.getValue()));
                if (dependency == null) throw new IllegalArgumentException("Unknown binding module: " + field.getValue());
                assign(RuntimeSupport.property(module, field.getKey()), dependency);
            }
        }
        return result;
    }
    public Object run(String module, String method, Object[] arguments) {
        return run(() -> {
            Module receiver = root.get(normalize(module));
            if (receiver == null) throw new BslException("Unknown module: " + module);
            return method == null ? UNDEFINED : call(receiver, method, arguments);
        });
    }
    public void runSource(String entryClass) {
        if (!explicitInstances) throw new IllegalStateException("Source mode is required");
        run(() -> { newModule(entryClass, new Object[0]); return UNDEFINED; });
    }
    /** Host entry point for Java embedding; all jobs belong to this scope. */
    public Object run(Callable<Object> action) {
        synchronized (this) {
            if (closed || started) throw new IllegalStateException("Application already started or closed");
            started = true;
        }
        try (var scope = Execution.enter(this, null, messages, context); var owner = context) {
            root = explicitInstances ? Map.of() : modules();
            return action.call();
        } catch (RuntimeException | Error e) { throw e; }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private Target target(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 1 || dot == name.length() - 1) throw new BslException("Имя задания должно иметь вид Модуль.Метод");
        Target result = new Target(normalize(name.substring(0, dot)), normalize(name.substring(dot + 1)));
        if (!factories.containsKey(result.module)) throw new BslException("Unknown background module: " + result.module);
        return result;
    }
    public Job execute(String method, Object parameters, Object key, String title) {
        Target target = target(method);
        if (root == null) throw new BslException("Задания запускаются после инициализации модулей, из точки входа приложения");
        Object supplied = read(parameters);
        if (supplied == UNDEFINED || supplied == Special.MISSING) supplied = new BArray();
        if (!(supplied instanceof BArray array)) throw new BslException("Параметры фонового задания должны быть массивом");
        if (!explicitInstances) validateCall(root.get(target.module), target.method, array.values.size());
        BArray snapshot = (BArray) ValueCopy.snapshot(array);
        Object copiedKey = ValueCopy.snapshot(key);
        if (copiedKey instanceof BigDecimal n) copiedKey = n.stripTrailingZeros();
        if (!(copiedKey == null || copiedKey instanceof Special || copiedKey instanceof String || copiedKey instanceof BigDecimal
                || copiedKey instanceof java.math.BigInteger || copiedKey instanceof Boolean || copiedKey instanceof java.time.LocalDateTime
                || copiedKey instanceof UUID || copiedKey instanceof Type))
            throw new BslException("Ключ фонового задания должен быть скалярным значением");
        Key unique = copiedKey == UNDEFINED || copiedKey == Special.MISSING ? null : new Key(target, copiedKey);
        synchronized (this) {
            if (closed) throw new BslException("Контекст фоновых заданий закрыт");
            Job parent = Execution.job();
            if (parent != null && parent.cancellationRequested()) throw new Execution.Cancelled();
            if (unique != null && keys.containsKey(unique)) throw new BslException("Задание с этим методом и ключом уже выполняется");
            Job job = new Job(target, snapshot, unique, title, parent);
            jobs.put(job.id, job); if (unique != null) keys.put(unique, job);
            try { job.task = pool.submit(() -> perform(job)); }
            catch (RejectedExecutionException e) {
                job.context.close();
                jobs.remove(job.id); if (unique != null) keys.remove(unique);
                throw new BslException("Очередь фоновых заданий заполнена или закрыта");
            }
            return job;
        }
    }
    private Object perform(Job job) {
        synchronized (this) {
            if (job.state.equals("Отменено")) return UNDEFINED;
            job.state = "Выполняется"; job.runner = Thread.currentThread();
        }
        Object result = UNDEFINED; Throwable failure = null;
        try (var scope = Execution.enter(this, job, job::message, job.context); var owner = job.context) {
            if (job.cancelRequested) throw new Execution.Cancelled();
            Module receiver = explicitInstances ? newModule(job.target.module, new Object[0])
                : modules().get(job.target.module);
            result = ValueCopy.snapshot(callNamed(receiver, job.target.method, job.parameters.values.toArray()));
        } catch (Throwable e) { failure = e; }
        finally { Thread.interrupted(); }
        synchronized (this) {
            job.runner = null; job.parameters = null;
            job.result = result; job.error = failure;
            job.state = job.cancelRequested || failure instanceof Execution.Cancelled ? "Отменено"
                : failure == null ? "Завершено" : "ЗавершеноСОшибкой";
            if (job.key != null) keys.remove(job.key);
            job.completion.countDown();
            trim();
        }
        // A child may have run on its parent's worker during join. Preserve parent cancellation.
        Job previous = Execution.job();
        if (previous != null && previous.cancelRequested) Thread.currentThread().interrupt();
        return UNDEFINED;
    }
    private void trim() {
        int completed = 0;
        for (Job job : jobs.values()) if (job.finished()) completed++;
        for (Iterator<Job> it = jobs.values().iterator(); completed > HISTORY && it.hasNext();) {
            Job job = it.next(); if (job.finished()) { it.remove(); completed--; }
        }
    }
    public void cancel(Job job) {
        if (job.owner() != this) throw new BslException("Задание принадлежит другому контексту");
        List<ApplicationContext> owners = new ArrayList<>();
        synchronized (this) {
            for (Job child : jobs.values()) if (child == job || child.descendsFrom(job)) cancelOne(child, owners);
            cancelOne(job, owners); // A completed handle may already have been evicted from history.
            trim();
        }
        cancelOwners(owners);
    }
    private void cancelOne(Job job, List<ApplicationContext> owners) {
        if (job.finished()) return;
        job.cancelRequested = true;
        owners.add(job.context);
        if (job.state.equals("ОжидаетВыполнения") && job.task.cancelQueued()) {
            job.state = "Отменено"; job.parameters = null;
            if (job.key != null) keys.remove(job.key);
            job.completion.countDown();
        } else if (job.runner != null) job.runner.interrupt();
    }
    private static void cancelOwners(List<ApplicationContext> owners) {
        ApplicationContext.cancelAll(owners);
    }
    public synchronized List<Job> jobs() { return List.copyOf(jobs.values()); }
    public Object property(String name) { throw new BslException("Unknown background manager property: " + name); }
    public Object invoke(String name, Object[] args) {
        return switch (name) {
            case "выполнить", "execute" -> {
                arity(args, 1, 4);
                yield execute(string(args[0]), args.length > 1 ? args[1] : UNDEFINED,
                    args.length > 2 ? args[2] : UNDEFINED, args.length > 3 ? string(args[3]) : string(args[0]));
            }
            case "получитьтекущеефоновоезадание", "getcurrentbackgroundjob" -> {
                arity(args, 0, 0); Job current = Execution.job(); yield current == null ? UNDEFINED : current;
            }
            case "получитьфоновыезадания", "getbackgroundjobs" -> {
                arity(args, 0, 0); BArray out = new BArray(); out.values.addAll(jobs()); yield out;
            }
            case "получитьфоновоезадание", "getbackgroundjob" -> {
                arity(args, 1, 1); synchronized (this) { yield jobs.getOrDefault(string(args[0]), null) == null ? UNDEFINED : jobs.get(string(args[0])); }
            }
            case "ожидатьзавершения", "waitforcompletion", "ожидатьзавершениявыполнения", "waitforexecutioncompletion" -> {
                arity(args, 1, 2);
                if (!(read(args[0]) instanceof BArray array)) throw new BslException("Ожидание требует массив заданий");
                long deadline = deadline(args.length > 1 ? args[1] : UNDEFINED); boolean complete = true;
                for (Object item : array.values) {
                    if (!(read(item) instanceof Job job) || job.owner() != this) throw new BslException("Ожидание требует задания этого контекста");
                    if (!job.awaitDeadline(deadline)) { complete = false; break; }
                }
                yield complete;
            }
            default -> throw new BslException("Unknown background manager method: " + name);
        };
    }
    private static void arity(Object[] args, int min, int max) {
        if (args.length < min || args.length > max) throw new BslException("Неверное число параметров фонового задания");
    }
    private static long deadline(Object seconds) {
        Object value = read(seconds);
        if (value == UNDEFINED || value == Special.MISSING) return Long.MAX_VALUE;
        BigDecimal timeout = decimal(value);
        if (timeout.signum() < 0) throw new BslException("Таймаут не может быть отрицательным");
        try { return Math.addExact(System.nanoTime(), timeout.multiply(BigDecimal.valueOf(1_000_000_000)).longValueExact()); }
        catch (ArithmeticException e) { throw new BslException("Таймаут слишком велик или содержит доли наносекунды"); }
    }
    public final class Job implements RuntimeSupport.NativeObject {
        private final String id = UUID.randomUUID().toString();
        private final Target target;
        private BArray parameters;
        private final Key key;
        private final String title;
        private final Job parent;
        private final ApplicationContext context;
        private volatile String state = "ОжидаетВыполнения";
        private volatile boolean cancelRequested;
        private Thread runner;
        private WorkPool.Task<Object> task;
        private Object result = UNDEFINED;
        private volatile Throwable error;
        private final CountDownLatch completion = new CountDownLatch(1);
        private final ArrayDeque<String> messages = new ArrayDeque<>();
        private long droppedMessages;
        private Job(Target target, BArray parameters, Key key, String title, Job parent) {
            this.target = target; this.parameters = parameters; this.key = key; this.title = title; this.parent = parent;
            this.context = Execution.context().snapshotWithoutStandardInput();
        }
        private BackgroundJobs owner() { return BackgroundJobs.this; }
        public String state() { return state; }
        public boolean cancellationRequested() { return cancelRequested; }
        private boolean finished() { return !state.equals("ОжидаетВыполнения") && !state.equals("Выполняется"); }
        private boolean descendsFrom(Job ancestor) { for (Job p = parent; p != null; p = p.parent) if (p == ancestor) return true; return false; }
        private synchronized void message(String text) {
            if (messages.size() == MESSAGE_LIMIT) { messages.removeFirst(); droppedMessages++; }
            messages.addLast(text);
        }
        public boolean await(Object timeout) { return awaitDeadline(deadline(timeout)); }
        private boolean awaitDeadline(long deadline) {
            if (Execution.job() == this) throw new BslException("Задание не может ожидать само себя");
            if (finished()) return true;
            try {
                if (deadline == Long.MAX_VALUE) task.join();
                else {
                    long remaining = deadline - System.nanoTime(); if (remaining <= 0) return finished();
                    task.get(remaining, TimeUnit.NANOSECONDS);
                }
            } catch (TimeoutException e) { return finished(); }
            catch (CancellationException e) {
                // Pool shutdown can cancel a Future before its running BSL body has stopped.
                try {
                    if (deadline == Long.MAX_VALUE) { completion.await(); return true; }
                    long remaining = deadline - System.nanoTime();
                    return remaining > 0 ? completion.await(remaining, TimeUnit.NANOSECONDS) : finished();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); Execution.checkCancellation(); throw new BslException("Ожидание прервано");
                }
            }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); Execution.checkCancellation(); throw new BslException("Ожидание прервано"); }
            catch (ExecutionException e) { throw new BslException("Ошибка ожидания: " + e.getCause()); }
            Execution.checkCancellation(); return finished();
        }
        public Object result() {
            await(UNDEFINED);
            if (state.equals("Отменено")) throw new BslException("Фоновое задание отменено");
            if (error != null) { BslException e = new BslException("Фоновое задание завершилось с ошибкой: " + error.getMessage()); e.initCause(error); throw e; }
            return ValueCopy.snapshot(result);
        }
        public Object property(String name) {
            return switch (name) {
                case "состояние", "state" -> state;
                case "уникальныйидентификатор", "id" -> id;
                case "наименование", "description" -> title;
                case "имяметода", "methodname" -> target.module + "." + target.method;
                case "ключ", "key" -> key == null ? UNDEFINED : key.value;
                case "результат", "result" -> result();
                case "информацияобошибке", "errorinfo" -> errorInfo(error);
                case "отменазапрошена", "cancellationrequested" -> cancelRequested;
                default -> throw new BslException("Unknown background job property: " + name);
            };
        }
        public Object invoke(String name, Object[] args) {
            return switch (name) {
                case "ожидатьзавершения", "waitforcompletion", "ожидатьзавершениявыполнения", "waitforexecutioncompletion" -> { arity(args, 0, 1); yield await(args.length == 0 ? UNDEFINED : args[0]); }
                case "получитьрезультат", "getresult" -> { arity(args, 0, 0); yield result(); }
                case "отменить", "cancel" -> { arity(args, 0, 0); cancel(this); yield UNDEFINED; }
                case "получитьсообщенияпользователю", "getusermessages" -> {
                    arity(args, 0, 0); BArray out = new BArray();
                    synchronized (this) { if (droppedMessages > 0) out.values.add("[Пропущено сообщений: " + droppedMessages + "]"); out.values.addAll(messages); }
                    yield out;
                }
                default -> throw new BslException("Unknown background job method: " + name);
            };
        }
        public String toString() { return "ФоновоеЗадание " + id + " (" + state + ")"; }
    }
    public void close() {
        List<ApplicationContext> owners = new ArrayList<>();
        synchronized (this) { closed = true; for (Job job : jobs.values()) cancelOne(job, owners); }
        try { cancelOwners(owners); }
        finally {
            try { pool.close(); }
            finally { context.close(); synchronized (this) { jobs.clear(); keys.clear(); root = null; } }
        }
    }
}
