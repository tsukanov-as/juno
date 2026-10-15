package juno.runtime;

import java.io.*;
import java.util.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Explicit resource APIs, independent of cwd and never an arbitrary classpath reader. */
final class ProjectLibrary {
    static void register(Map<String,GlobalFunctions.Entry> entries) {
        GlobalFunctions.add(entries, "ИменаРесурсов", "ResourceNames", 0, 0, a -> {
            BArray names = new BArray(true);
            names.values.addAll(Execution.context().program().resources().names());
            return names;
        });
        GlobalFunctions.add(entries, "ПолучитьРесурс", "GetResource", 1, 1, a -> new Binary(bytes(a[0])));
        GlobalFunctions.add(entries, "ПолучитьТекстРесурса", "GetResourceText", 1, 2,
            a -> TextEncoding.value(optional(a, 1, "UTF-8")).decode(bytes(a[0])));
    }
    private static byte[] bytes(Object value) {
        if (!(value instanceof String name) || name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":"))
            throw error("Resource name requires a portable relative path");
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.codePoints().anyMatch(c -> c < 32 || c == 127))
                throw error("Resource path traversal/invalid segment");
        }
        ApplicationProgram.Resources resources = Execution.context().program().resources();
        if (!resources.names().contains(name)) throw error("Неизвестный ресурс: " + name);
        try (InputStream input = resources.open(name); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] block = new byte[65536];
            int n;
            while ((n = input.read(block)) >= 0) {
                Execution.checkCancellation();
                if (output.size() > 16 * 1024 * 1024 - n) throw error("Resource getter exceeds16MiB");
                output.write(block, 0, n);
            }
            return output.toByteArray();
        } catch (IOException e) { throw failure("Чтение ресурса " + name, e); }
    }
}
