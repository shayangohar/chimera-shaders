// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render;

import net.chimera.shaderpack.ProgramImageBindingManifest;
import net.chimera.shaderpack.SelectorNamespace;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Binds a program's images to their selector slots and restores the previous bindings on close. Generic over
 * the slot state, so tests need no Vulkan.
 */
public final class ProgramImageBindingTransaction<S> implements AutoCloseable {
    public interface Store<S> {
        S capture(int slot);
        void bind(int slot, S value);
        void restore(int slot, S value);
        boolean available(S value);
    }

    private final Store<S> store;
    private final Map<Integer, S> previous = new LinkedHashMap<>();
    private boolean closed;

    private ProgramImageBindingTransaction(Store<S> store) { this.store = store; }

    public static <S> ProgramImageBindingTransaction<S> bind(String program,
            ProgramImageBindingManifest manifest, Store<S> store,
            Function<ProgramImageBindingManifest.Entry, S> resolver) {
        for (var entry : manifest.entries()) {
            if (!SelectorNamespace.isAddressable(entry.slot())) {
                throw new IllegalArgumentException("SELECTOR_SLOT_UNSUPPORTED:" + entry.slot());
            }
        }
        ProgramImageBindingTransaction<S> transaction = new ProgramImageBindingTransaction<>(store);
        try {
            // capture every slot before resolving, so host aliases see the state before this transaction
            for (var entry : manifest.entries()) {
                if (!transaction.previous.containsKey(entry.slot())) {
                    transaction.previous.put(entry.slot(), store.capture(entry.slot()));
                }
            }
            Map<Integer, S> resolved = new LinkedHashMap<>();
            for (var entry : manifest.entries()) {
                S value;
                try {
                    value = resolver.apply(entry);
                    if (!store.available(value)) throw new IllegalStateException("missing resource");
                } catch (RuntimeException failure) {
                    throw new IllegalStateException("RESOURCE_BINDING_UNAVAILABLE:" + program + ":"
                            + entry.symbol() + ":slot=" + entry.slot(), failure);
                }
                resolved.put(entry.slot(), value);
            }
            for (var entry : resolved.entrySet()) store.bind(entry.getKey(), entry.getValue());
            return transaction;
        } catch (RuntimeException | Error failure) {
            try { transaction.close(); } catch (RuntimeException | Error rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }

    public boolean containsSlot(int slot) { return previous.containsKey(slot); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        Throwable failure = null;
        for (var entry : previous.entrySet()) {
            try { store.restore(entry.getKey(), entry.getValue()); }
            catch (RuntimeException | Error error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }
}
