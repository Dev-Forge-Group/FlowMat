package org.myweb.flowmat.domain.workflow.annotation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.workflow.annotation.api.dto.request.CanvasAnnotationBatchRequest;
import org.myweb.flowmat.domain.workflow.annotation.api.dto.request.CanvasAnnotationBatchRequest.CanvasAnnotationBatchItemRequest;
import org.myweb.flowmat.domain.workflow.annotation.api.dto.request.CanvasAnnotationCreateRequest;
import org.myweb.flowmat.domain.workflow.annotation.api.dto.request.CanvasAnnotationPatchRequest;
import org.myweb.flowmat.domain.workflow.annotation.api.dto.response.CanvasAnnotationResponse;
import org.myweb.flowmat.domain.workflow.annotation.domain.CanvasAnnotation;
import org.myweb.flowmat.domain.workflow.annotation.repository.CanvasAnnotationRepository;
import org.myweb.flowmat.domain.workflow.application.WorkflowAccessService;
import org.myweb.flowmat.domain.workflow.collab.GraphSyncService;
import org.myweb.flowmat.domain.workflow.collab.dto.GraphChangeMessage;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
class CanvasAnnotationServiceTest {

    private static final String WORKFLOW_ID = "wf-1";
    private static final String PROJECT_ID = "proj-1";

    @Mock
    private CanvasAnnotationRepository canvasAnnotationRepository;

    @Mock
    private ProjectAccessService projectAccessService;

    @Mock
    private WorkflowAccessService workflowAccess;

    @Mock
    private FractionalIndexService fractionalIndexService;

    @Mock
    private CanvasAnnotationReconcileService reconcileService;

    @Mock
    private GraphSyncService graphSyncService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private CanvasAnnotationService canvasAnnotationService;

    private Workflow workflow;

    @BeforeEach
    void setUp() {
        workflow = new Workflow();
        workflow.setWorkflowId(WORKFLOW_ID);
        workflow.setProjectId(PROJECT_ID);
    }

    @Test
    void listRequiresReadAccessAndReturnsMappedAnnotations() {
        when(workflowAccess.requireWorkflowReadAccess(WORKFLOW_ID)).thenReturn(workflow);
        CanvasAnnotation stored = shapeAnnotation("ann-1");
        when(canvasAnnotationRepository.findAllByWorkflowIdAndDeletedYnOrderByZIndexAscCreatedAtAsc(WORKFLOW_ID, "N"))
            .thenReturn(List.of(stored));

        List<CanvasAnnotationResponse> result = canvasAnnotationService.list(WORKFLOW_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).annotationId()).isEqualTo("ann-1");
    }

    @Test
    void createShapeWithoutShapeKindIsRejected() {
        when(workflowAccess.requireWorkflowWriteAccess(WORKFLOW_ID)).thenReturn(workflow);
        when(canvasAnnotationRepository.findAllByWorkflowIdAndDeletedYnOrderByZIndexAscCreatedAtAsc(WORKFLOW_ID, "N"))
            .thenReturn(List.of());
        CanvasAnnotationCreateRequest request = createRequest("shape", null, null, "New note");

        assertThatThrownBy(() -> canvasAnnotationService.create(WORKFLOW_ID, request))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void createFreehandWithoutPointsIsRejected() {
        when(workflowAccess.requireWorkflowWriteAccess(WORKFLOW_ID)).thenReturn(workflow);
        when(canvasAnnotationRepository.findAllByWorkflowIdAndDeletedYnOrderByZIndexAscCreatedAtAsc(WORKFLOW_ID, "N"))
            .thenReturn(List.of());
        CanvasAnnotationCreateRequest request = createRequest("freehand", null, null, null);

        assertThatThrownBy(() -> canvasAnnotationService.create(WORKFLOW_ID, request))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void createTextWithoutTextContentIsRejected() {
        when(workflowAccess.requireWorkflowWriteAccess(WORKFLOW_ID)).thenReturn(workflow);
        when(canvasAnnotationRepository.findAllByWorkflowIdAndDeletedYnOrderByZIndexAscCreatedAtAsc(WORKFLOW_ID, "N"))
            .thenReturn(List.of());
        CanvasAnnotationCreateRequest request = createRequest("text", null, null, null);

        assertThatThrownBy(() -> canvasAnnotationService.create(WORKFLOW_ID, request))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void createValidShapeSavesAndBroadcastsCreation() {
        when(workflowAccess.requireWorkflowWriteAccess(WORKFLOW_ID)).thenReturn(workflow);
        when(projectAccessService.requireCurrentUserId()).thenReturn("user-1");
        when(canvasAnnotationRepository.findAllByWorkflowIdAndDeletedYnOrderByZIndexAscCreatedAtAsc(WORKFLOW_ID, "N"))
            .thenReturn(List.of());
        when(fractionalIndexService.between(null, null)).thenReturn("1024");
        CanvasAnnotationCreateRequest request = createRequest("shape", "rectangle", null, null);

        CanvasAnnotationResponse response = canvasAnnotationService.create(WORKFLOW_ID, request);

        assertThat(response.annotationType()).isEqualTo("shape");
        assertThat(response.shapeKind()).isEqualTo("rectangle");
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.zIndex()).isEqualTo("1024");
        verify(canvasAnnotationRepository).save(any(CanvasAnnotation.class));
        verify(graphSyncService).broadcast(eq(GraphChangeMessage.Type.ANNOTATION_CREATED), eq(WORKFLOW_ID), anyString());
    }

    @Test
    void patchDiscardsStaleIncomingVersionWithoutBroadcasting() {
        CanvasAnnotation existing = shapeAnnotation("ann-1");
        existing.setWorkflowId(WORKFLOW_ID);
        when(workflowAccess.requireWorkflowWriteAccess(WORKFLOW_ID)).thenReturn(workflow);
        when(canvasAnnotationRepository.findByAnnotationIdAndDeletedYn("ann-1", "N"))
            .thenReturn(Optional.of(existing));
        when(reconcileService.shouldDiscardIncoming(existing, 1, 5L)).thenReturn(true);
        CanvasAnnotationPatchRequest request = new CanvasAnnotationPatchRequest(
            10d, 20d, null, null, null, null, null, null, null, null, null, 1, 5L
        );

        CanvasAnnotationResponse response = canvasAnnotationService.patch(WORKFLOW_ID, "ann-1", request);

        assertThat(response.posX()).isNotEqualTo(10d);
        verify(canvasAnnotationRepository, never()).save(any());
        verify(graphSyncService, never()).broadcast(any(), anyString(), anyString());
    }

    @Test
    void patchAppliesUpdateAndBumpsVersion() {
        CanvasAnnotation existing = shapeAnnotation("ann-1");
        existing.setWorkflowId(WORKFLOW_ID);
        when(workflowAccess.requireWorkflowWriteAccess(WORKFLOW_ID)).thenReturn(workflow);
        when(projectAccessService.requireCurrentUserId()).thenReturn("user-1");
        when(canvasAnnotationRepository.findByAnnotationIdAndDeletedYn("ann-1", "N"))
            .thenReturn(Optional.of(existing));
        when(reconcileService.shouldDiscardIncoming(existing, 1, 5L)).thenReturn(false);
        CanvasAnnotationPatchRequest request = new CanvasAnnotationPatchRequest(
            10d, 20d, null, null, null, null, null, null, null, null, null, 1, 5L
        );

        CanvasAnnotationResponse response = canvasAnnotationService.patch(WORKFLOW_ID, "ann-1", request);

        assertThat(response.posX()).isEqualTo(10d);
        assertThat(response.posY()).isEqualTo(20d);
        assertThat(response.version()).isEqualTo(2);
        verify(graphSyncService).broadcast(eq(GraphChangeMessage.Type.ANNOTATION_UPDATED), eq(WORKFLOW_ID), eq("ann-1"));
    }

    @Test
    void patchOnMissingAnnotationThrowsNotFound() {
        when(workflowAccess.requireWorkflowWriteAccess(WORKFLOW_ID)).thenReturn(workflow);
        when(canvasAnnotationRepository.findByAnnotationIdAndDeletedYn("missing", "N")).thenReturn(Optional.empty());
        CanvasAnnotationPatchRequest request = new CanvasAnnotationPatchRequest(
            null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        assertThatThrownBy(() -> canvasAnnotationService.patch(WORKFLOW_ID, "missing", request))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void deleteMarksSoftDeletedAndBroadcasts() {
        CanvasAnnotation existing = shapeAnnotation("ann-1");
        existing.setWorkflowId(WORKFLOW_ID);
        when(workflowAccess.requireWorkflowWriteAccess(WORKFLOW_ID)).thenReturn(workflow);
        when(projectAccessService.requireCurrentUserId()).thenReturn("user-1");
        when(canvasAnnotationRepository.findByAnnotationIdAndDeletedYn("ann-1", "N"))
            .thenReturn(Optional.of(existing));

        canvasAnnotationService.delete(WORKFLOW_ID, "ann-1");

        assertThat(existing.getDeletedYn()).isEqualTo("Y");
        verify(graphSyncService).broadcast(eq(GraphChangeMessage.Type.ANNOTATION_DELETED), eq(WORKFLOW_ID), eq("ann-1"));
    }

    @Test
    void batchUpdateAppliesPositionToEveryItem() {
        CanvasAnnotation first = shapeAnnotation("ann-1");
        first.setWorkflowId(WORKFLOW_ID);
        CanvasAnnotation second = shapeAnnotation("ann-2");
        second.setWorkflowId(WORKFLOW_ID);
        when(workflowAccess.requireWorkflowWriteAccess(WORKFLOW_ID)).thenReturn(workflow);
        when(workflowAccess.requireWorkflowReadAccess(WORKFLOW_ID)).thenReturn(workflow);
        when(projectAccessService.requireCurrentUserId()).thenReturn("user-1");
        when(canvasAnnotationRepository.findByAnnotationIdAndDeletedYn("ann-1", "N")).thenReturn(Optional.of(first));
        when(canvasAnnotationRepository.findByAnnotationIdAndDeletedYn("ann-2", "N")).thenReturn(Optional.of(second));
        when(canvasAnnotationRepository.findAllByWorkflowIdAndDeletedYnOrderByZIndexAscCreatedAtAsc(WORKFLOW_ID, "N"))
            .thenReturn(List.of(first, second));
        CanvasAnnotationBatchRequest request = new CanvasAnnotationBatchRequest(List.of(
            new CanvasAnnotationBatchItemRequest("ann-1", 1d, 2d, null, null, null, null, null),
            new CanvasAnnotationBatchItemRequest("ann-2", 3d, 4d, null, null, null, null, null)
        ));

        List<CanvasAnnotationResponse> result = canvasAnnotationService.batchUpdate(WORKFLOW_ID, request);

        assertThat(result).extracting(CanvasAnnotationResponse::posX).containsExactlyInAnyOrder(1d, 3d);
        verify(graphSyncService, times(2))
            .broadcast(eq(GraphChangeMessage.Type.ANNOTATION_UPDATED), eq(WORKFLOW_ID), anyString(), eq("user-1"));
    }

    private CanvasAnnotationCreateRequest createRequest(
        String annotationType,
        String shapeKind,
        JsonNode points,
        String textContent
    ) {
        return new CanvasAnnotationCreateRequest(
            annotationType,
            shapeKind,
            0d,
            0d,
            100d,
            100d,
            0d,
            points,
            textContent,
            null,
            null,
            null
        );
    }

    private CanvasAnnotation shapeAnnotation(String id) {
        CanvasAnnotation annotation = new CanvasAnnotation();
        annotation.setAnnotationId(id);
        annotation.setWorkflowId(WORKFLOW_ID);
        annotation.setProjectId(PROJECT_ID);
        annotation.setAnnotationType("shape");
        annotation.setShapeKind("rectangle");
        annotation.setPosX(0d);
        annotation.setPosY(0d);
        annotation.setWidth(100d);
        annotation.setHeight(100d);
        annotation.setRotation(0d);
        annotation.setZIndex("1024");
        annotation.setDeletedYn("N");
        annotation.setVersion(1);
        annotation.setVersionNonce(1L);
        return annotation;
    }
}
