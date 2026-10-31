package org.myweb.flowmat.domain.connector.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorHttpCancellation;

class ConnectorHttpCancellationTest {
    @Test
    void cancellationIsIdempotentAndAlsoInvokesRegistrationsMadeAfterCancellation() {
        var cancellation = new ConnectorHttpCancellation();
        AtomicInteger before = new AtomicInteger();
        AtomicInteger after = new AtomicInteger();
        cancellation.onCancel(before::incrementAndGet);

        assertThat(cancellation.isCancelled()).isFalse();
        cancellation.cancel();
        cancellation.cancel();
        cancellation.onCancel(after::incrementAndGet);

        assertThat(cancellation.isCancelled()).isTrue();
        assertThat(before).hasValue(1);
        assertThat(after).hasValue(1);
    }

    @Test
    void closingRegistrationRemovesItsListenerAndCanBeRepeated() {
        var cancellation = new ConnectorHttpCancellation();
        AtomicInteger removed = new AtomicInteger();
        AtomicInteger retained = new AtomicInteger();
        var registration = cancellation.onCancel(removed::incrementAndGet);
        cancellation.onCancel(retained::incrementAndGet);

        registration.close();
        registration.close();
        cancellation.cancel();
        registration.close();

        assertThat(removed).hasValue(0);
        assertThat(retained).hasValue(1);
    }

    @Test
    void concurrentRegistrationAndCancellationRunEachRetainedActionExactlyOnce() throws Exception {
        var cancellation = new ConnectorHttpCancellation();
        var ready = new CountDownLatch(68);
        var start = new CountDownLatch(1);
        List<AtomicInteger> invocations = new ArrayList<>();
        List<Future<?>> tasks = new ArrayList<>();
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < 64; index++) {
                AtomicInteger invoked = new AtomicInteger();
                invocations.add(invoked);
                tasks.add(callers.submit(() -> {
                    ready.countDown();
                    await(start);
                    cancellation.onCancel(invoked::incrementAndGet);
                }));
            }
            for (int index = 0; index < 4; index++) {
                tasks.add(callers.submit(() -> {
                    ready.countDown();
                    await(start);
                    cancellation.cancel();
                }));
            }
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> task : tasks) task.get(2, TimeUnit.SECONDS);
        } finally {
            start.countDown();
        }

        assertThat(cancellation.isCancelled()).isTrue();
        for (AtomicInteger invoked : invocations) assertThat(invoked).hasValue(1);
    }

    @Test
    void failingActionDoesNotEscapeOrPreventOtherActions() {
        var cancellation = new ConnectorHttpCancellation();
        AtomicInteger retained = new AtomicInteger();
        cancellation.onCancel(() -> { throw new IllegalStateException("cancel callback failed"); });
        cancellation.onCancel(retained::incrementAndGet);

        assertThatCode(cancellation::cancel).doesNotThrowAnyException();
        assertThatCode(() -> cancellation.onCancel(() -> {
            throw new IllegalStateException("late cancel callback failed");
        })).doesNotThrowAnyException();

        assertThat(retained).hasValue(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("Concurrent callers did not start.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
