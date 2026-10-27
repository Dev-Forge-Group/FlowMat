package org.myweb.flowmat.global.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.myweb.flowmat.global.response.ApiResponse;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Expected write conflicts must retain their category without copying driver rows or request values into logs. */
class ConflictLoggingPrivacyRegressionTest {
    private static final String MARKER = "PRIVATE_ROW_CANARY";
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private boolean additive;
    private Level previousLevel;

    @BeforeEach
    void captureConflictDiagnosticsWithoutSendingCanariesToOtherAppenders() {
        additive = logger.isAdditive();
        previousLevel = logger.getLevel();
        logger.setAdditive(false);
        logger.setLevel(Level.INFO);
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void restoreLogger() {
        logger.detachAppender(events);
        events.stop();
        logger.setLevel(previousLevel);
        logger.setAdditive(additive);
    }

    @ParameterizedTest
    @EnumSource(Conflict.class)
    void aConflictStillReturns409WithoutLoggingPrivateExceptionContent(Conflict kind) {
        ResponseEntity<ApiResponse<Void>> response = kind.handle(handler);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ApiResponse.error(kind.responseMessage()), response.getBody());
        assertFalse(events.list.isEmpty(), "The conflict category should remain observable.");
        for (ILoggingEvent event : events.list) {
            assertEquals(Level.INFO, event.getLevel());
            assertFalse(event.getFormattedMessage().contains(MARKER), "Private conflict content appeared in a diagnostic message.");
            assertNull(event.getThrowableProxy(), "A driver exception containing private rows was attached to the conflict log.");
            assertTrue(event.getFormattedMessage().toLowerCase().contains("conflict"));
            Object[] arguments = event.getArgumentArray();
            if (arguments != null) {
                for (Object argument : arguments) {
                    assertFalse(String.valueOf(argument).contains(MARKER), "A private value remained in structured log arguments.");
                }
            }
        }
    }

    private enum Conflict {
        CONSTRAINT_MESSAGE,
        CONSTRAINT_NESTED_DRIVER,
        OPTIMISTIC_MESSAGE,
        OPTIMISTIC_NESTED_DRIVER;

        ResponseEntity<ApiResponse<Void>> handle(GlobalExceptionHandler handler) {
            String row = "Failing row contains (" + MARKER + ").";
            return switch (this) {
                case CONSTRAINT_MESSAGE -> handler.handleDataIntegrityViolation(new DataIntegrityViolationException(row));
                case CONSTRAINT_NESTED_DRIVER -> handler.handleDataIntegrityViolation(new DataIntegrityViolationException(
                    "JDBC statement failed", new SQLException(row, "23514")));
                case OPTIMISTIC_MESSAGE -> handler.handleOptimisticLock(new OptimisticLockingFailureException(row));
                case OPTIMISTIC_NESTED_DRIVER -> handler.handleOptimisticLock(new OptimisticLockingFailureException(
                    "Conflicting entity " + MARKER, new SQLException(row, "40001")));
            };
        }

        String responseMessage() {
            return switch (this) {
                case CONSTRAINT_MESSAGE, CONSTRAINT_NESTED_DRIVER ->
                    "This conflicts with a change that was just saved. Reload and try again.";
                case OPTIMISTIC_MESSAGE, OPTIMISTIC_NESTED_DRIVER -> ErrorCode.CONFLICT.getMessage();
            };
        }
    }
}
