package org.myweb.flowmat.domain.workflow.collab;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.workflow.annotation.repository.CanvasAnnotationRepository;
import org.myweb.flowmat.domain.workflow.application.WorkflowAccessService;
import org.myweb.flowmat.domain.workflow.collab.dto.GraphChangeMessage;
import org.myweb.flowmat.domain.workflow.collab.dto.GraphChangeMessage.Type;
import org.myweb.flowmat.domain.workflow.repository.ProcessConnectionRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessIoRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessRepository;
import org.myweb.flowmat.domain.workflow.repository.WorkflowRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class GraphSyncServiceTest {
    @Mock SimpMessagingTemplate messages;
    @Mock RedisGraphChangeStore changes;
    @Mock ProcessRepository processes;
    @Mock ProcessIoRepository ports;
    @Mock ProcessConnectionRepository connections;
    @Mock WorkflowRepository workflows;
    @Mock WorkflowAccessService access;
    @Mock CanvasAnnotationRepository annotations;
    @Spy ObjectMapper mapper = new ObjectMapper();
    @InjectMocks GraphSyncService service;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deletionWithNullPayloadIsPublishedOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        GraphChangeMessage change = GraphChangeMessage.of(1, Type.NODE_DELETED, "wf-1", "node-1", "user-1", 1, null);
        when(changes.append(eq(Type.NODE_DELETED), eq("wf-1"), eq("node-1"), eq("user-1"), isNull()))
            .thenReturn(change);

        service.broadcast(Type.NODE_DELETED, "wf-1", "node-1", "user-1");
        verifyNoInteractions(changes, messages);
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) sync.afterCommit();

        verify(changes).append(eq(Type.NODE_DELETED), eq("wf-1"), eq("node-1"), eq("user-1"), isNull());
        verify(messages).convertAndSend("/topic/workflow/wf-1/graph", change);
    }

    @Test
    void rolledBackSaveDoesNotAppendOrBroadcast() {
        TransactionSynchronizationManager.initSynchronization();
        service.broadcast(Type.CONNECTION_DELETED, "wf-1", "edge-1", "user-1");
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        verify(changes, never()).append(eq(Type.CONNECTION_DELETED), eq("wf-1"), eq("edge-1"), eq("user-1"), isNull());
        verifyNoInteractions(messages);
    }

    @Test
    void redisFailureAfterCommitSendsSnapshotResetSignal() {
        TransactionSynchronizationManager.initSynchronization();
        when(changes.append(eq(Type.NODE_DELETED), eq("wf-1"), eq("node-1"), eq("user-1"), isNull()))
            .thenThrow(new IllegalStateException("Redis unavailable"));

        service.broadcast(Type.NODE_DELETED, "wf-1", "node-1", "user-1");
        assertDoesNotThrow(() -> {
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) sync.afterCommit();
        });

        ArgumentCaptor<Object> signal = ArgumentCaptor.forClass(Object.class);
        verify(messages).convertAndSend(eq("/topic/workflow/wf-1/graph"), signal.capture());
        GraphChangeMessage reset = (GraphChangeMessage) signal.getValue();
        assertEquals("RESET_REQUIRED", reset.changeType());
        assertEquals("wf-1", reset.workflowId());
    }
}
