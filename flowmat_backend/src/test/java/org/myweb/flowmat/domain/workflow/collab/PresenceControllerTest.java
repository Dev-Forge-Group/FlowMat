package org.myweb.flowmat.domain.workflow.collab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.workflow.application.WorkflowAccessService;
import org.myweb.flowmat.domain.workflow.collab.dto.PresenceMessage;
import org.myweb.flowmat.domain.workflow.collab.dto.AnnotationPresencePayload;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.security.AuthUser;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class PresenceControllerTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private WorkflowSessionRegistry sessionRegistry;

    @Mock
    private WorkflowAccessService workflowAccess;

    @InjectMocks
    private PresenceController presenceController;

    @Test
    void heartbeatUpdatesSessionWithoutBroadcast() {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
        headers.setSessionId("session-1");
        PresenceMessage heartbeat = new PresenceMessage(
            PresenceMessage.Type.HEARTBEAT,
            null,
            "client-1",
            null,
            null,
            null,
            null,
            null,
            0L
        );

        presenceController.relay(
            "workflow-1",
            heartbeat,
            new UsernamePasswordAuthenticationToken(new AuthUser("user-1"), null),
            headers
        );

        verify(sessionRegistry).touch("session-1", "user-1", "workflow-1", heartbeat);
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void leaveRemovesSessionAndBroadcastsLeave() {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
        headers.setSessionId("session-2");

        presenceController.relay(
            "workflow-9",
            new PresenceMessage(PresenceMessage.Type.LEAVE, null, "client-9", null, null, null, null, null, 0L),
            new UsernamePasswordAuthenticationToken(new AuthUser("user-9"), null),
            headers
        );

        verify(sessionRegistry).remove("session-2");

        ArgumentCaptor<PresenceMessage> captor = ArgumentCaptor.forClass(PresenceMessage.class);
        verify(messagingTemplate).convertAndSend(org.mockito.ArgumentMatchers.eq("/topic/workflow/workflow-9/presence"), captor.capture());

        PresenceMessage relayed = captor.getValue();
        assertThat(relayed.type()).isEqualTo(PresenceMessage.Type.LEAVE);
        assertThat(relayed.userId()).isEqualTo("user-9");
        assertThat(relayed.clientId()).isEqualTo("client-9");
        assertThat(relayed.workflowId()).isEqualTo("workflow-9");
    }

    @Test
    void nodeEditingWithoutProcessIsRelayedAsEditingNothing() {
        // Deselecting a process sends NODE_EDITING with no process: collaborators drop the editing mark and the session
        // record forgets it. Dropping the message left the mark on the last process for everyone else.
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
        headers.setSessionId("session-3");
        PresenceMessage stopped = new PresenceMessage(PresenceMessage.Type.NODE_EDITING, null, "client-3", null, null, null,
            null, null, 0L);

        presenceController.relay("workflow-3", stopped, principal(), headers);

        verify(sessionRegistry).touch("session-3", "user-1", "workflow-3", stopped);
        ArgumentCaptor<PresenceMessage> captor = ArgumentCaptor.forClass(PresenceMessage.class);
        verify(messagingTemplate).convertAndSend(org.mockito.ArgumentMatchers.eq("/topic/workflow/workflow-3/presence"),
            captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(PresenceMessage.Type.NODE_EDITING);
        assertThat(captor.getValue().editingProcessId()).isNull();
        assertThat(captor.getValue().userId()).isEqualTo("user-1");
    }

    @Test
    void rejectsNonMemberBeforeRelaying() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
            .when(workflowAccess).requireWorkflowReadAccess("workflow-1", "user-1");
        assertThatThrownBy(() -> presenceController.relay("workflow-1",
            new PresenceMessage(PresenceMessage.Type.JOIN, null, "client-1", null, null, null, null, null, 0),
            principal(), SimpMessageHeaderAccessor.create()))
            .isInstanceOf(BusinessException.class);
        verifyNoInteractions(sessionRegistry, messagingTemplate);
    }

    @Test
    void dropsMalformedPresenceWithoutBroadcast() {
        var invalid = List.of(
            new PresenceMessage(PresenceMessage.Type.JOIN, null, null, null, null, null, null, null, 0),
            new PresenceMessage(PresenceMessage.Type.JOIN, null, " ", null, null, null, null, null, 0),
            new PresenceMessage(PresenceMessage.Type.JOIN, null, "x".repeat(129), null, null, null, null, null, 0),
            new PresenceMessage(PresenceMessage.Type.CURSOR_MOVED, null, "client", null, Double.NaN, 1d, null, null, 0),
            new PresenceMessage(PresenceMessage.Type.CURSOR_MOVED, null, "client", null, 1_000_001d, 1d, null, null, 0),
            new PresenceMessage(PresenceMessage.Type.NODE_EDITING, null, "client", null, null, null, " ", null, 0),
            new PresenceMessage(PresenceMessage.Type.NODE_EDITING, null, "client", null, null, null, "p".repeat(129), null, 0),
            new PresenceMessage(PresenceMessage.Type.ANNOTATION_DRAWING, null, "client", null, null, null, null,
                new AnnotationPresencePayload("freehand", List.of(List.of(Double.NaN, 1d)), true), 0)
        );
        for (PresenceMessage message : invalid) {
            presenceController.relay("workflow-1", message, principal(), SimpMessageHeaderAccessor.create());
        }
        verifyNoInteractions(sessionRegistry, messagingTemplate);
    }

    private UsernamePasswordAuthenticationToken principal() {
        return new UsernamePasswordAuthenticationToken(new AuthUser("user-1"), null);
    }
}
