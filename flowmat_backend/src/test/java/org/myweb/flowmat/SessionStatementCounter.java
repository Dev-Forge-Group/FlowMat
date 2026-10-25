package org.myweb.flowmat;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.hibernate.SessionEventListener;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Counts statements prepared by the current JPA session and thread within a test measurement. */
public final class SessionStatementCounter implements SessionEventListener, AutoCloseable {

    private static final long serialVersionUID = 1L;

    private final Thread owner = Thread.currentThread();
    private volatile boolean closed;
    private long statements;

    private SessionStatementCounter() {
    }

    public static SessionStatementCounter start(EntityManagerFactory factory) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Statement counting requires an active JPA transaction.");
        }
        Object resource = TransactionSynchronizationManager.getResource(factory);
        if (!(resource instanceof EntityManagerHolder holder)) {
            throw new IllegalStateException("Statement counting requires a transaction-bound EntityManager.");
        }
        SessionStatementCounter counter = new SessionStatementCounter();
        holder.getEntityManager().unwrap(Session.class).addEventListeners(counter);
        return counter;
    }

    @Override
    public void jdbcPrepareStatementStart() {
        if (Thread.currentThread() == owner && !closed) {
            statements++;
        }
    }

    public long count() {
        return statements;
    }

    @Override
    public void close() {
        closed = true;
    }
}
