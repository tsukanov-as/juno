package juno;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Deterministic ZIP metadata, including the manifest entry. */
final class Archives {
    interface Content { InputStream open() throws IOException; }
    static Content bytes(byte[] bytes) { return () -> new ByteArrayInputStream(bytes); }
    static Content file(Path path) { return () -> Files.newInputStream(path); }

    static void write(Path target, String main, SortedMap<String,Content> entries) throws IOException {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(target))) {
            put(jar, "META-INF/MANIFEST.MF", bytes(("Manifest-Version: 1.0\r\nMain-Class: " + main + "\r\n\r\n").getBytes(StandardCharsets.UTF_8)));
            for (var item : entries.entrySet()) put(jar, item.getKey(), item.getValue());
        }
    }
    private static void put(JarOutputStream jar, String name, Content content) throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTime(0);
        jar.putNextEntry(entry);
        try (InputStream in = content.open()) { in.transferTo(jar); }
        jar.closeEntry();
    }
}
