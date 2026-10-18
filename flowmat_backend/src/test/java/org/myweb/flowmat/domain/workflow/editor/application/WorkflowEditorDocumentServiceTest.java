package org.myweb.flowmat.domain.workflow.editor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.workflow.application.WorkflowAccessService;
import org.myweb.flowmat.domain.workflow.collab.GraphSyncService;
import org.myweb.flowmat.domain.workflow.collab.dto.GraphChangeMessage;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.myweb.flowmat.domain.workflow.editor.api.dto.request.EditorDocumentSaveRequest;
import org.myweb.flowmat.domain.workflow.editor.api.dto.request.EditorElementRequest;
import org.myweb.flowmat.domain.workflow.editor.api.dto.response.EditorDocumentResponse;
import org.myweb.flowmat.domain.workflow.editor.domain.WorkflowEditorDocument;
import org.myweb.flowmat.domain.workflow.editor.domain.WorkflowEditorElement;
import org.myweb.flowmat.domain.workflow.editor.repository.WorkflowEditorDocumentRepository;
import org.myweb.flowmat.domain.workflow.editor.repository.WorkflowEditorElementRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
class WorkflowEditorDocumentServiceTest {

    private static final String WORKFLOW_ID = "wf-1";
    private static final String PROJECT_ID = "proj-1";

    @Mock
    private WorkflowEditorDocumentRepository documentRepository;

    @Mock
    private WorkflowEditorElementRepository elementRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private ProjectAccessService projectAccessService;

    @Mock
    private WorkflowAccessService workflowAccess;

    @Mock
    private GraphSyncService graphSyncService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private WorkflowEditorDocumentService service;

    private Workflow workflow;

    @BeforeEach
    void setUp() {
        workflow = new Workflow();
        workflow.setWorkflowId(WORKFLOW_ID);
        workflow.setProjectId(PROJECT_ID);
    }

    @Test
    void getDocumentRequiresReadAccessAndReturnsDefaultDocumentWhenMissing() {
        when(workflowAccess.requireWorkflowReadAccess(WORKFLOW_ID)).thenReturn(workflow);
        when(documentRepository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());
        when(elementRepository.findAllByWorkflowIdAndDeletedYnOrderByElementOrderAscCreatedAtAsc(WORKFLOW_ID, "N"))
            .thenReturn(List.of());

        EditorDocumentResponse response = service.getDocument(WORKFLOW_ID);

        assertThat(response.schemaVersion()).isEqualTo(1);
        assertThat(response.camera().get("zoom").asDouble()).isEqualTo(1d);
        assertThat(response.nextElementSeq()).isEqualTo(1);
        assertThat(response.elements()).isEmpty();
    }

    @Test
    void saveDocumentPersistsPolygonAndLineElements() {
        when(workflowAccess.requireWorkflowWriteAccess(WORKFLOW_ID)).thenReturn(workflow);
        when(projectAccessService.requireCurrentUserId()).thenReturn("user-1");
        when(documentRepository.findByWorkflowIdForUpdate(WORKFLOW_ID)).thenReturn(Optional.empty());
        when(elementRepository.findAllByWorkflowIdAndDeletedYnOrderByElementOrderAscCreatedAtAsc(WORKFLOW_ID, "N"))
            .thenReturn(List.of());
        EditorDocumentSaveRequest request = new EditorDocumentSaveRequest(
            1,
            camera(20, 30, 1.5),
            3,
            List.of(
                element("tri-1", "polygon", 1, polygonGeometry()),
                element("line-1", "line", 2, lineGeometry())
            )
        );

        EditorDocumentResponse response = service.saveDocument(WORKFLOW_ID, request);

        assertThat(response.elements()).extracting("id").containsExactly("tri-1", "line-1");
        assertThat(response.elements()).extracting("type").containsExactly("polygon", "line");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<WorkflowEditorElement>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(elementRepository).saveAll(captor.capture());
        assertThat(captor.getValue())
            .extracting(WorkflowEditorElement::getElementType)
            .containsExactly("polygon", "line");
        verify(documentRepository).save(any(WorkflowEditorDocument.class));
        verify(entityManager).lock(workflow, LockModeType.PESSIMISTIC_WRITE);
        verify(graphSyncService).broadcast(
            GraphChangeMessage.Type.EDITOR_DOCUMENT_UPDATED,
            WORKFLOW_ID,
            WORKFLOW_ID,
            "user-1"
        );
    }

    @Test
    void saveDocumentSoftDeletesElementsMissingFromFullDocumentSave() {
        WorkflowEditorDocument document = document();
        WorkflowEditorElement stale = storedElement("old-1", "rectangle", 1);
        when(workflowAccess.requireWorkflowWriteAccess(WORKFLOW_ID)).thenReturn(workflow);
        when(projectAccessService.requireCurrentUserId()).thenReturn("user-1");
        when(documentRepository.findByWorkflowIdForUpdate(WORKFLOW_ID)).thenReturn(Optional.of(document));
        when(elementRepository.findAllByWorkflowIdAndDeletedYnOrderByElementOrderAscCreatedAtAsc(WORKFLOW_ID, "N"))
            .thenReturn(List.of(stale));
        EditorDocumentSaveRequest request = new EditorDocumentSaveRequest(1, camera(0, 0, 1), 1, List.of());

        EditorDocumentResponse response = service.saveDocument(WORKFLOW_ID, request);

        assertThat(response.elements()).isEmpty();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<WorkflowEditorElement>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(elementRepository).saveAll(captor.capture());
        assertThat(captor.getValue())
            .extracting(WorkflowEditorElement::getDeletedYn)
            .containsExactly("Y");
    }

    @Test
    void saveDocumentRejectsLineWithoutEndpoints() {
        when(workflowAccess.requireWorkflowWriteAccess(WORKFLOW_ID)).thenReturn(workflow);
        EditorDocumentSaveRequest request = new EditorDocumentSaveRequest(
            1,
            camera(0, 0, 1),
            2,
            List.of(element("line-1", "line", 1, objectMapper.createObjectNode()))
        );

        assertThatThrownBy(() -> service.saveDocument(WORKFLOW_ID, request))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void saveDocumentRejectsDuplicateElementIds() {
        when(workflowAccess.requireWorkflowWriteAccess(WORKFLOW_ID)).thenReturn(workflow);
        EditorDocumentSaveRequest request = new EditorDocumentSaveRequest(
            1,
            camera(0, 0, 1),
            3,
            List.of(
                element("shape-1", "rectangle", 1, objectMapper.createObjectNode()),
                element("shape-1", "ellipse", 2, objectMapper.createObjectNode())
            )
        );

        assertThatThrownBy(() -> service.saveDocument(WORKFLOW_ID, request))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    private EditorElementRequest element(String id, String type, int order, JsonNode geometry) {
        return new EditorElementRequest(
            id,
            type,
            10d,
            20d,
            120d,
            80d,
            0d,
            1d,
            null,
            false,
            false,
            order,
            geometry,
            objectMapper.createObjectNode().put("stroke", "#111827")
        );
    }

    private JsonNode camera(double x, double y, double zoom) {
        return objectMapper.createObjectNode()
            .put("x", x)
            .put("y", y)
            .put("zoom", zoom);
    }

    private JsonNode polygonGeometry() {
        return objectMapper.createObjectNode()
            .put("closed", true)
            .set("points", objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("x", 60).put("y", 0))
                .add(objectMapper.createObjectNode().put("x", 120).put("y", 80))
                .add(objectMapper.createObjectNode().put("x", 0).put("y", 80)));
    }

    private JsonNode lineGeometry() {
        var geometry = objectMapper.createObjectNode();
        geometry.set("start", objectMapper.createObjectNode().put("x", 0).put("y", 0));
        geometry.set("end", objectMapper.createObjectNode().put("x", 120).put("y", 80));
        return geometry;
    }

    private WorkflowEditorDocument document() {
        WorkflowEditorDocument document = new WorkflowEditorDocument();
        document.setWorkflowId(WORKFLOW_ID);
        document.setProjectId(PROJECT_ID);
        document.setSchemaVersion(1);
        document.setCameraJson("{\"x\":0,\"y\":0,\"zoom\":1}");
        document.setNextElementSeq(1);
        document.setVersion(1);
        document.setVersionNonce(1L);
        document.setDeletedYn("N");
        return document;
    }

    private WorkflowEditorElement storedElement(String id, String type, int order) {
        WorkflowEditorElement element = new WorkflowEditorElement();
        element.setEditorElementId("ede-" + id);
        element.setWorkflowId(WORKFLOW_ID);
        element.setProjectId(PROJECT_ID);
        element.setElementId(id);
        element.setElementType(type);
        element.setPosX(0d);
        element.setPosY(0d);
        element.setWidth(100d);
        element.setHeight(100d);
        element.setRotation(0d);
        element.setOpacity(1d);
        element.setElementOrder(order);
        element.setGeometryJson("{}");
        element.setStyleJson("{}");
        element.setLockedYn("N");
        element.setHiddenYn("N");
        element.setDeletedYn("N");
        element.setVersion(1);
        element.setVersionNonce(1L);
        return element;
    }
}
