package juno.runtime;

import java.io.*;
import java.util.*;

/** Immutable per-application source snapshots and explicitly supplied resource provider. */
public final class ApplicationProgram {
    public record Source(String file, String text) {
        public String line(int number) {
            List<String> lines = text.lines().toList();
            return number > 0 && number <= lines.size() ? lines.get(number - 1) : "";
        }
    }
    public interface Resources {
        List<String> names();
        InputStream open(String name) throws IOException;
    }
    public static final ApplicationProgram EMPTY = new ApplicationProgram(Map.of(), new Resources() {
        public List<String> names() { return List.of(); }
        public InputStream open(String name) throws IOException { throw new FileNotFoundException(name); }
    });
    private final Map<String,Source> sources;
    private final Resources resources;
    public ApplicationProgram(Map<String,Source> sources, Resources resources) {
        this.sources = Map.copyOf(sources);
        this.resources = Objects.requireNonNull(resources);
    }
    public Source source(String className) { return sources.get(className); }
    public Resources resources() { return resources; }
}
