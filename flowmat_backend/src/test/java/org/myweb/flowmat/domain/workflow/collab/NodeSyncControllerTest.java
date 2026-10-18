package org.myweb.flowmat.domain.workflow.collab;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.workflow.application.WorkflowAccessService;
import org.myweb.flowmat.domain.workflow.collab.dto.NodeMoveMessage;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.security.AuthUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class NodeSyncControllerTest {
    @Mock WorkflowAccessService access;
    @InjectMocks NodeSyncController controller;

    @Test
    void movementRequiresWriteAccessEvenWhenHandlerCalledDirectly() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
            .when(access).requireWorkflowWriteAccess("wf-1", "viewer-1");

        assertThatThrownBy(() -> controller.relay("wf-1", message(),
            new UsernamePasswordAuthenticationToken(new AuthUser("viewer-1"), null)))
            .isInstanceOf(BusinessException.class);
        verify(access).requireWorkflowWriteAccess("wf-1", "viewer-1");
    }

    @Test
    void invalidCoordinatesAreRejected() {
        var invalid = new NodeMoveMessage("node-1", Double.NaN, 1, null, "client-1", null, 0);
        assertThatThrownBy(() -> controller.relay("wf-1", invalid,
            new UsernamePasswordAuthenticationToken(new AuthUser("editor-1"), null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private NodeMoveMessage message() {
        return new NodeMoveMessage("node-1", 1, 2, null, "client-1", null, 0);
    }
}
