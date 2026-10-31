package org.myweb.flowmat.domain.flowrun.application;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class FlowRunHttpInFlightRequestsTest {
    private final FlowRunHttpInFlightRequests requests = new FlowRunHttpInFlightRequests();
    private final TestTransactions transactions = new TestTransactions();
    private final TransactionTemplate transaction = new TransactionTemplate(transactions);

    @Test
    void aCommitCancelsEveryParallelRequestForTheRunAfterTheDatabaseCommit() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var ready = new CyclicBarrier(2);
            var firstFuture = workers.submit(() -> {
                ready.await(5, TimeUnit.SECONDS);
                return requests.track("run", "first");
            });
            var secondFuture = workers.submit(() -> {
                ready.await(5, TimeUnit.SECONDS);
                return requests.track("run", "second");
            });
            var first = firstFuture.get(5, TimeUnit.SECONDS);
            var second = secondFuture.get(5, TimeUnit.SECONDS);
            try (first; second; var unrelated = requests.track("other-run", "third")) {
                var cancellations = new AtomicInteger();
                first.cancellation().onCancel(() -> {
                    assertTrue(transactions.committed);
                    cancellations.incrementAndGet();
                });
                second.cancellation().onCancel(() -> {
                    assertTrue(transactions.committed);
                    cancellations.incrementAndGet();
                });

                transaction.executeWithoutResult(status -> {
                    requests.cancelAfterCommit("run");
                    assertFalse(first.cancellation().isCancelled());
                    assertFalse(second.cancellation().isCancelled());
                    assertEquals(0, cancellations.get());
                });

                assertTrue(first.cancellation().isCancelled());
                assertTrue(second.cancellation().isCancelled());
                assertFalse(unrelated.cancellation().isCancelled());
                assertEquals(2, cancellations.get());
            }
        }
    }

    @Test
    void rollbackDoesNotCancelAndDoesNotLeakItsCallbackIntoAnotherTransaction() {
        try (var request = requests.track("run", "execution")) {
            var cancellations = new AtomicInteger();
            request.cancellation().onCancel(cancellations::incrementAndGet);

            transaction.executeWithoutResult(status -> {
                requests.cancelAfterCommit("run");
                status.setRollbackOnly();
            });
            transaction.executeWithoutResult(status -> { });

            assertFalse(request.cancellation().isCancelled());
            assertEquals(0, cancellations.get());
            assertTrue(transactions.rolledBack);

            transaction.executeWithoutResult(status -> requests.cancelAfterCommit("run"));
            assertTrue(request.cancellation().isCancelled());
            assertEquals(1, cancellations.get());
        }
    }

    @Test
    void duplicateCancellationCallbacksAreIdempotent() {
        try (var request = requests.track("run", "execution")) {
            var cancellations = new AtomicInteger();
            request.cancellation().onCancel(cancellations::incrementAndGet);

            transaction.executeWithoutResult(status -> {
                requests.cancelAfterCommit("run");
                requests.cancelAfterCommit("run");
            });
            transaction.executeWithoutResult(status -> requests.cancelAfterCommit("run"));

            assertEquals(1, cancellations.get());
        }
    }

    @Test
    void closingAnExecutionRemovesOnlyThatLiveRequest() {
        var closed = requests.track("run", "closed");
        closed.close();
        closed.close();
        try (var live = requests.track("run", "live")) {
            transaction.executeWithoutResult(status -> requests.cancelAfterCommit("run"));

            assertFalse(closed.cancellation().isCancelled());
            assertTrue(live.cancellation().isCancelled());
        }
    }

    @Test
    void closingAnOldHandleDoesNotRemoveAReplacementWithTheSameExecutionId() {
        var old = requests.track("run", "execution");
        try (var replacement = requests.track("run", "execution")) {
            old.close();
            transaction.executeWithoutResult(status -> requests.cancelAfterCommit("run"));

            assertFalse(old.cancellation().isCancelled());
            assertTrue(replacement.cancellation().isCancelled());
        } finally {
            old.close();
        }
    }

    @Test
    void executionIdsAreScopedToTheirRun() {
        try (var first = requests.track("first-run", "execution");
             var second = requests.track("second-run", "execution")) {
            transaction.executeWithoutResult(status -> requests.cancelAfterCommit("first-run"));

            assertTrue(first.cancellation().isCancelled());
            assertFalse(second.cancellation().isCancelled());
        }
    }

    @Test
    void aCancellationWithoutAnActualTransactionDoesNotSendACancellation() {
        try (var request = requests.track("run", "execution")) {
            requests.cancelAfterCommit("run");

            assertFalse(request.cancellation().isCancelled());
        }
    }

    @Test
    void synchronizationAloneDoesNotAuthorizeCancellation() {
        try (var request = requests.track("run", "execution")) {
            TransactionSynchronizationManager.initSynchronization();
            try {
                requests.cancelAfterCommit("run");
                for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                    synchronization.afterCommit();
                }
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }

            assertFalse(request.cancellation().isCancelled());
        }
    }

    @Test
    void anActualTransactionWithoutSynchronizationDoesNotSendACancellation() {
        try (var request = requests.track("run", "execution")) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertDoesNotThrow(() -> requests.cancelAfterCommit("run"));
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }

            assertFalse(request.cancellation().isCancelled());
        }
    }

    @Test
    void oneFailingCancellationCallbackCannotFailCommitOrStopOtherRequests() {
        try (var failing = requests.track("run", "failing");
             var healthy = requests.track("run", "healthy")) {
            var cancellations = new AtomicInteger();
            failing.cancellation().onCancel(() -> { throw new IllegalStateException("transport already closed"); });
            healthy.cancellation().onCancel(cancellations::incrementAndGet);

            assertDoesNotThrow(() -> transaction.executeWithoutResult(status -> requests.cancelAfterCommit("run")));

            assertTrue(transactions.committed);
            assertTrue(failing.cancellation().isCancelled());
            assertTrue(healthy.cancellation().isCancelled());
            assertEquals(1, cancellations.get());
        }
    }

    @Test
    void cancellationCallbacksDoNotHoldTheRegistryLock() {
        try (var workers = Executors.newSingleThreadExecutor();
             var request = requests.track("run", "execution")) {
            var callbacks = new AtomicInteger();
            request.cancellation().onCancel(() -> {
                try (var another = workers.submit(() -> requests.track("other-run", "execution"))
                    .get(5, TimeUnit.SECONDS)) {
                    assertFalse(another.cancellation().isCancelled());
                    callbacks.incrementAndGet();
                } catch (Exception failure) {
                    throw new AssertionError("Cancellation blocked a different caller from registering", failure);
                }
            });

            transaction.executeWithoutResult(status -> requests.cancelAfterCommit("run"));

            assertEquals(1, callbacks.get());
        }
    }

    @Test
    void aFinishedCancellationDoesNotCreateATombstoneForLaterRequests() {
        transaction.executeWithoutResult(status -> requests.cancelAfterCommit("run"));

        try (var later = requests.track("run", "later")) {
            assertFalse(later.cancellation().isCancelled());
        }
    }

    private static final class TestTransactions extends AbstractPlatformTransactionManager {
        private boolean committed;
        private boolean rolledBack;

        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { committed = true; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rolledBack = true; }
    }
}
