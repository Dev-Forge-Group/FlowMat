package org.myweb.flowmat.domain.flowrun.application;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorHttpCancellation;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Cancellation handles exist only while this process owns a live HTTP execution. */
@Component
public class FlowRunHttpInFlightRequests {
    private final ConcurrentMap<RequestKey, Registration> requests = new ConcurrentHashMap<>();

    public Registration track(String runId, String executionId) {
        var key = new RequestKey(Objects.requireNonNull(runId), Objects.requireNonNull(executionId));
        var registration = new Registration(this, key);
        requests.put(key, registration);
        return registration;
    }

    public void cancelAfterCommit(String runId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cancelRun(runId);
            }
        });
    }

    private void cancelRun(String runId) {
        var matching = requests.entrySet().stream()
            .filter(entry -> entry.getKey().runId().equals(runId))
            .map(entry -> entry.getValue().cancellation())
            .toList();
        for (var cancellation : matching) {
            try {
                cancellation.cancel();
            } catch (RuntimeException ignored) {
                // The run cancellation already committed; one transport must not prevent the others from closing.
            }
        }
    }

    private record RequestKey(String runId, String executionId) { }

    public static final class Registration implements AutoCloseable {
        private final FlowRunHttpInFlightRequests owner;
        private final RequestKey key;
        private final ConnectorHttpCancellation cancellation = new ConnectorHttpCancellation();

        private Registration(FlowRunHttpInFlightRequests owner, RequestKey key) {
            this.owner = owner;
            this.key = key;
        }

        public ConnectorHttpCancellation cancellation() {
            return cancellation;
        }

        @Override
        public void close() {
            owner.requests.remove(key, this);
        }
    }
}
