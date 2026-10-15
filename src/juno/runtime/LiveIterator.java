package juno.runtime;

import java.util.*;

/**
 * Iteration of Для Каждого over a collection that may change in the loop, as in 1C: by position, looking at the
 * current size at every step. Added elements are visited, removing the current one skips the next, Очистить ends the loop.
 */
final class LiveIterator implements Iterator<Object> {
    private final List<?> items;
    private int position;
    LiveIterator(List<?> items) { this.items = items; }
    public boolean hasNext() { return position < items.size(); }
    public Object next() {
        if (position >= items.size()) throw new NoSuchElementException();
        return items.get(position++);
    }
}
