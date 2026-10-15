package juno.runtime;

import java.util.*;
import juno.runtime.RuntimeSupport.Module;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Lazy shared module instances owned by one execution context. */
final class ModuleRegistry implements NativeObject, TypedValue {
    private final ApplicationContext owner;
    private final BackgroundJobs application;
    private final Map<String, String> names = new TreeMap<>();
    private final Map<String, Module> instances = new HashMap<>();
    private final Set<String> initializing = new LinkedHashSet<>();

    ModuleRegistry(ApplicationContext owner, BackgroundJobs application) {
        this.owner = owner;
        this.application = application;
        for (String name : application.moduleNames()) names.put(name.toLowerCase(Locale.ROOT), name);
    }

    private void requireContext() {
        owner.requireOpen();
        if (Execution.currentContext() != owner || Execution.currentApplication() != application)
            throw error("Реестр модулей принадлежит другому контексту выполнения");
    }

    private String name(Object value) {
        if (!(value instanceof String text)) throw error("Модули: имя модуля должно быть строкой");
        return text.toLowerCase(Locale.ROOT);
    }

    private synchronized Object get(String key) {
        requireContext();
        String name = names.get(key);
        if (name == null) throw error("Неизвестный модуль: " + key);
        Module existing = instances.get(name);
        if (existing != null) return existing;
        if (!initializing.add(name)) {
            List<String> chain = new ArrayList<>(initializing);
            chain.add(name);
            throw error("Циклическая инициализация модулей: " + String.join(" → ", chain));
        }
        try {
            Module instance = application.newModule(name, new Object[0]);
            owner.requireOpen();
            instances.put(name, instance);
            return instance;
        } finally {
            initializing.remove(name);
        }
    }

    public Object invoke(String method, Object[] raw) {
        requireContext();
        Object[] args = values(raw);
        return switch (method) {
            case "получить", "get" -> { arity(method, args, 1, 1); yield get(name(args[0])); }
            case "содержит", "contains" -> { arity(method, args, 1, 1); yield names.containsKey(name(args[0])); }
            case "имена", "names" -> {
                arity(method, args, 0, 0);
                BArray result = new BArray(true);
                result.values.addAll(names.values());
                yield result;
            }
            default -> missingMethod(bslTypeName(), method);
        };
    }

    public Object property(String name) { requireContext(); return missingProperty(bslTypeName(), name); }
    public String bslTypeName() { return "Модули"; }
    public String toString() { return bslTypeName(); }
}
