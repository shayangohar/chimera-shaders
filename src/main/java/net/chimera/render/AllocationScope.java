// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import java.util.function.Supplier;

/**
 * A value this thread holds while it makes one thing, for a hook deep in shared allocation code to read. Only
 * the allocation this thread opened changes, and the outer value comes back however the call ends.
 */
public final class AllocationScope<T> {
    private final ThreadLocal<T> current = new ThreadLocal<>();

    /** What {@code body} makes while {@code value} is current on this thread. */
    public <R> R with(T value, Supplier<R> body) {
        T outer = this.current.get();
        this.current.set(value);
        try {
            return body.get();
        } finally {
            if (outer == null) this.current.remove();
            else this.current.set(outer);
        }
    }

    public void run(T value, Runnable body) {
        with(value, () -> {
            body.run();
            return null;
        });
    }

    /** The value of the allocation this thread is making; null outside one. */
    public T current() {
        return this.current.get();
    }
}
