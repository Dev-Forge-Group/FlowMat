package org.myweb.flowmat.domain.connector.application.publicapi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Cancels one in-process dispatch without interrupting its calling thread. */
public final class ConnectorHttpCancellation {
    private final Object lock = new Object();
    private final Map<Object, Runnable> listeners = new LinkedHashMap<>();
    private volatile boolean cancelled;

    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        List<Runnable> pending;
        synchronized (lock) {
            if (cancelled) return;
            cancelled = true;
            pending = new ArrayList<>(listeners.values());
            listeners.clear();
        }
        // Callbacks may cancel transport futures; never invoke them while holding the registry lock.
        pending.forEach(ConnectorHttpCancellation::invoke);
    }

    public Registration onCancel(Runnable action) {
        Objects.requireNonNull(action, "action");
        Object identity = new Object();
        synchronized (lock) {
            if (!cancelled) {
                listeners.put(identity, action);
                return () -> {
                    synchronized (lock) {
                        listeners.remove(identity);
                    }
                };
            }
        }
        invoke(action);
        return () -> {};
    }

    private static void invoke(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // One cleanup failure must not hide a committed stop or skip other cleanup callbacks.
        }
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
