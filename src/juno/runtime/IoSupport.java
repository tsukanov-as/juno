package juno.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static juno.runtime.RuntimeSupport.*;

/** Shared validation for native I/O objects and the global registry. */
final class IoSupport {
    private IoSupport() {}
    static Object[] values(Object[] args) { Object[] values = new Object[args.length]; for (int i = 0; i < args.length; i++) values[i] = read(args[i]); return values; }
    static boolean omitted(Object value) { return value == UNDEFINED || value == Special.MISSING; }
    static Object optional(Object[] args, int index, Object fallback) { return index >= args.length || omitted(read(args[index])) ? fallback : read(args[index]); }
    static void arity(String name, Object[] args, int min, int max) {
        if (args.length < min || args.length > max) throw error(name + ": ожидается " + min + (min == max ? "" : ".." + max) + " аргументов");
        for (int i = 0; i < min; i++) if (omitted(read(args[i]))) throw error(name + ": пропущен аргумент " + (i + 1));
    }
    static int integer(Object value) { try { return decimal(value).intValueExact(); } catch (ArithmeticException e) { throw error("Ожидается целое число в диапазоне int"); } }
    static long longInteger(Object value) { try { return decimal(value).longValueExact(); } catch (ArithmeticException e) { throw error("Ожидается целое число в диапазоне long"); } }
    static int nonnegative(Object value) { int n = integer(value); if (n < 0) throw error("Число не может быть отрицательным"); return n; }
    static Path path(Object value) {
        if (!(value instanceof String s) || s.isEmpty()) throw error("Ожидается непустой путь к файлу");
        return Execution.context().resolve(Path.of(s));
    }
    static void regular(Path path) {
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path))
            throw error("Ожидался обычный файл (каталоги и специальные устройства не поддерживаются): "+path);
    }
    static BslException error(String text) { return new BslException(text); }
    static BslException failure(String operation, Exception cause) {
        Execution.checkCancellation();
        BslException error = error(operation + ": " + RuntimeSupport.description(cause)); error.initCause(cause); return error;
    }
    static Object missingMethod(String type, String name) { throw error(type + ": неподдерживаемый метод " + name); }
    static Object missingProperty(String type, String name) { throw error(type + ": неподдерживаемое свойство " + name); }
}
