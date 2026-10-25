package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.hibernate.Session;
import org.hibernate.SessionEventListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SessionStatementCounterTest {

    private final EntityManagerFactory entityManagerFactory = mock(EntityManagerFactory.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final Session session = mock(Session.class);

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.unbindResourceIfPossible(entityManagerFactory);
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void countsPreparedStatementsOnTheBoundSession() {
        bindActiveSession();

        try (SessionStatementCounter counter = SessionStatementCounter.start(entityManagerFactory)) {
            SessionEventListener listener = registeredListener(1);
            assertEquals(0L, counter.count());

            listener.jdbcPrepareStatementStart();
            listener.jdbcPrepareStatementStart();

            assertEquals(2L, counter.count());
            verify(entityManager).unwrap(Session.class);
        }
    }

    @Test
    void ignoresCallbacksFromAnotherThreadWhileTheOwnerStillCounts() throws Exception {
        bindActiveSession();

        try (SessionStatementCounter counter = SessionStatementCounter.start(entityManagerFactory)) {
            SessionEventListener listener = registeredListener(1);

            CompletableFuture.runAsync(listener::jdbcPrepareStatementStart).get(5, TimeUnit.SECONDS);

            assertEquals(0L, counter.count());
            listener.jdbcPrepareStatementStart();
            assertEquals(1L, counter.count());
        }
    }

    @Test
    void closedCounterKeepsItsCountAndANewCounterOnTheSameSessionStartsFresh() {
        bindActiveSession();
        SessionStatementCounter first = SessionStatementCounter.start(entityManagerFactory);
        SessionEventListener firstListener = registeredListener(1);

        try (first) {
            firstListener.jdbcPrepareStatementStart();
        }
        firstListener.jdbcPrepareStatementStart();
        assertEquals(1L, first.count());

        try (SessionStatementCounter second = SessionStatementCounter.start(entityManagerFactory)) {
            SessionEventListener secondListener = registeredListener(2);
            assertEquals(0L, second.count());

            firstListener.jdbcPrepareStatementStart();
            secondListener.jdbcPrepareStatementStart();

            assertEquals(1L, first.count());
            assertEquals(1L, second.count());
        }
    }

    @Test
    void rejectsABoundEntityManagerWithoutAnActualTransaction() {
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(entityManager));
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThrows(IllegalStateException.class, () -> SessionStatementCounter.start(entityManagerFactory));

        verifyNoInteractions(session);
    }

    @Test
    void rejectsAnActualTransactionWithoutABoundJpaEntityManager() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThrows(IllegalStateException.class, () -> SessionStatementCounter.start(entityManagerFactory));

        verifyNoInteractions(session);
    }

    @Test
    void exceptionInsideTryWithResourcesStillDeactivatesTheCounter() {
        bindActiveSession();
        SessionStatementCounter counter = SessionStatementCounter.start(entityManagerFactory);
        SessionEventListener listener = registeredListener(1);
        IllegalArgumentException expected = new IllegalArgumentException("Measured operation failed");

        IllegalArgumentException actual = assertThrows(IllegalArgumentException.class, () -> {
            try (counter) {
                listener.jdbcPrepareStatementStart();
                throw expected;
            }
        });

        assertSame(expected, actual);
        listener.jdbcPrepareStatementStart();
        assertEquals(1L, counter.count());
    }

    private void bindActiveSession() {
        when(entityManager.unwrap(Session.class)).thenReturn(session);
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(entityManager));
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private SessionEventListener registeredListener(int registrations) {
        ArgumentCaptor<SessionEventListener> captor = ArgumentCaptor.forClass(SessionEventListener.class);
        verify(session, times(registrations)).addEventListeners(captor.capture());
        return captor.getAllValues().get(registrations - 1);
    }
}
