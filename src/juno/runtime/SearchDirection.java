package juno.runtime;

import java.util.Iterator;
import java.util.List;

/** The search direction is an enum value, not a number or its string presentation. */
public enum SearchDirection {
    FROM_BEGINNING("СНачала"), FROM_END("СКонца");

    private final String display;
    SearchDirection(String display) { this.display = display; }
    @Override public String toString() { return display; }

    static final Namespace VALUES = new Namespace();
    static final class Namespace implements RuntimeSupport.NativeObject, Iterable<Object> {
        public Object property(String name) {
            return switch (name) {
                case "сначала", "frombegin" -> FROM_BEGINNING;
                case "сконца", "fromend" -> FROM_END;
                default -> throw new RuntimeSupport.BslException("Unknown SearchDirection property: " + name);
            };
        }
        public Object invoke(String name, Object[] arguments) {
            throw new RuntimeSupport.BslException("Unknown SearchDirection method: " + name);
        }
        public Iterator<Object> iterator() { return List.<Object>of(FROM_BEGINNING, FROM_END).iterator(); }
        @Override public String toString() { return "ПеречислениеНаправлениеПоиска"; }
    }
}
