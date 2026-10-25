package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.persistence.EntityManagerFactory;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Timeout(45)
class SessionStatementCounterIntegrationTest extends IntegrationTestSupport {
    @Autowired EntityManagerFactory entities;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void queryCountExcludesStatementsFromAnotherThread() {
        var executor = Executors.newSingleThreadExecutor();
        try {
            long count = measure(() -> {
                selectOne();
                Future<?> unrelated = executor.submit(() ->
                        new TransactionTemplate(transactions).executeWithoutResult(tx -> selectOne()));
                await(unrelated);
                selectOne();
            });

            assertEquals(2L, count, "Only the two statements in the measured session should count.");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void queryCountExcludesStatementsFromRequiresNewTransaction() {
        var unrelated = new TransactionTemplate(transactions);
        unrelated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        long count = measure(() -> {
            selectOne();
            unrelated.executeWithoutResult(tx -> selectOne());
            selectOne();
        });

        assertEquals(2L, count, "The suspended session must not count the separate transaction's statement.");
    }

    @Test
    void separateMeasurementsEachCountTheirOwnNativeQuery() {
        assertEquals(1L, measure(this::selectOne));
        assertEquals(1L, measure(this::selectOne));
    }

    @Test
    void queryCountDetectsTwelveStatements() {
        long count = measure(() -> {
            for (int i = 0; i < 12; i++) {
                selectOne();
            }
        });

        assertEquals(12L, count, "Repeated statements must remain visible so N+1 regressions are detected.");
    }

    private long measure(Runnable action) {
        return new TransactionTemplate(transactions).execute(tx -> {
            try (SessionStatementCounter counter = SessionStatementCounter.start(entities)) {
                action.run();
                return counter.count();
            }
        });
    }

    private void selectOne() {
        EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(entities);
        assertNotNull(holder, "The query must use the EntityManager bound to the current transaction.");
        holder.getEntityManager().createNativeQuery("select 1").getSingleResult();
    }

    private static void await(Future<?> future) {
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the unrelated transaction.", interrupted);
        } catch (ExecutionException failed) {
            throw new AssertionError("The unrelated transaction failed.", failed.getCause());
        } catch (TimeoutException timedOut) {
            throw new AssertionError("The unrelated transaction did not finish within ten seconds.", timedOut);
        }
    }
}
