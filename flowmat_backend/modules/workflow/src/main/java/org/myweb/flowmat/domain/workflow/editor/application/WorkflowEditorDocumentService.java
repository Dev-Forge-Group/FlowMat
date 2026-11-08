package org.myweb.flowmat.domain.workflow.editor.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.workflow.application.WorkflowAccessService;
import org.myweb.flowmat.domain.workflow.collab.GraphSyncService;
import org.myweb.flowmat.domain.workflow.collab.dto.GraphChangeMessage;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.myweb.flowmat.domain.workflow.editor.api.dto.request.EditorDocumentSaveRequest;
import org.myweb.flowmat.domain.workflow.editor.api.dto.request.EditorElementRequest;
import org.myweb.flowmat.domain.workflow.editor.api.dto.response.EditorDocumentResponse;
import org.myweb.flowmat.domain.workflow.editor.api.dto.response.EditorElementResponse;
import org.myweb.flowmat.domain.workflow.editor.domain.WorkflowEditorDocument;
import org.myweb.flowmat.domain.workflow.editor.domain.WorkflowEditorElement;
import org.myweb.flowmat.domain.workflow.editor.repository.WorkflowEditorDocumentRepository;
import org.myweb.flowmat.domain.workflow.editor.repository.WorkflowEditorElementRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class WorkflowEditorDocumentService {

    private static final String NOT_DELETED = "N";
    private static final String DELETED = "Y";
    private static final Set<String> SUPPORTED_TYPES = Set.of(
        "rectangle", "ellipse", "polygon", "line", "freehand", "text", "group"
    );

    private final WorkflowEditorDocumentRepository documentRepository;
    private final WorkflowEditorElementRepository elementRepository;
    private final EntityManager entityManager;
    private final ProjectAccess projectAccessService;
    private final WorkflowAccessService workflowAccess;
    private final ObjectMapper objectMapper;
    private final GraphSyncService graphSyncService;

    @Transactional(readOnly = true)
    public EditorDocumentResponse getDocument(String workflowId) {
        Workflow workflow = workflowAccess.requireWorkflowReadAccess(workflowId);
        WorkflowEditorDocument document = documentRepository.findById(workflow.getWorkflowId()).orElse(null);
        List<WorkflowEditorElement> elements = elementRepository
            .findAllByWorkflowIdAndDeletedYnOrderByElementOrderAscCreatedAtAsc(workflow.getWorkflowId(), NOT_DELETED);
        return toDocumentResponse(document, elements);
    }

    public EditorDocumentResponse saveDocument(String workflowId, EditorDocumentSaveRequest request) {
        Workflow workflow = workflowAccess.requireWorkflowWriteAccess(workflowId);
        validateDocument(request);
        // The document row does not exist on its first save. Lock the parent row
        // so concurrent creators serialize before checking expectedVersion.
        entityManager.lock(workflow, LockModeType.PESSIMISTIC_WRITE);
        String userId = projectAccessService.requireCurrentUserId();
        long nonce = System.currentTimeMillis();

        WorkflowEditorDocument document = documentRepository.findByWorkflowIdForUpdate(workflow.getWorkflowId())
            .orElseGet(() -> newDocument(workflow, userId));
        if (request.expectedVersion() != null && request.expectedVersion() != document.getVersion()) {
            throw new BusinessException(
                ErrorCode.CONFLICT,
                "Editor document version " + document.getVersion() + " is current."
            );
        }
        document.setSchemaVersion(request.schemaVersion() == null ? 1 : request.schemaVersion());
        document.setCameraJson(toJsonOrDefault(request.camera()));
        document.setNextElementSeq(request.nextElementSeq() == null ? request.elements().size() + 1 : request.nextElementSeq());
        document.setUpdatedBy(userId);
        document.setVersion(document.getVersion() + 1);
        document.setVersionNonce(nonce);
        documentRepository.save(document);

        List<WorkflowEditorElement> existing = elementRepository
            .findAllByWorkflowIdAndDeletedYnOrderByElementOrderAscCreatedAtAsc(workflow.getWorkflowId(), NOT_DELETED);
        Map<String, WorkflowEditorElement> existingByElementId = new LinkedHashMap<>();
        for (WorkflowEditorElement element : existing) {
            existingByElementId.put(element.getElementId(), element);
        }

        Set<String> incomingIds = request.elements().stream()
            .map(item -> item.id().trim())
            .collect(java.util.stream.Collectors.toSet());
        List<WorkflowEditorElement> toSave = new ArrayList<>();
        for (WorkflowEditorElement element : existing) {
            if (!incomingIds.contains(element.getElementId())) {
                element.setDeletedYn(DELETED);
                element.setUpdatedBy(userId);
                element.setVersion(element.getVersion() + 1);
                element.setVersionNonce(nonce);
                toSave.add(element);
            }
        }

        List<WorkflowEditorElement> activeElements = new ArrayList<>();
        for (int i = 0; i < request.elements().size(); i++) {
            EditorElementRequest item = request.elements().get(i);
            String elementId = item.id().trim();
            WorkflowEditorElement element = existingByElementId.get(elementId);
            if (element == null) {
                element = newElement(workflow, elementId, userId);
            }
            applyElement(element, workflow, item, i + 1, userId, nonce);
            toSave.add(element);
            activeElements.add(element);
        }
        elementRepository.saveAll(toSave);

        activeElements.sort(Comparator.comparing(WorkflowEditorElement::getElementOrder));
        EditorDocumentResponse response = toDocumentResponse(document, activeElements);
        graphSyncService.broadcast(
            GraphChangeMessage.Type.EDITOR_DOCUMENT_UPDATED,
            workflow.getWorkflowId(),
            workflow.getWorkflowId(),
            userId
        );
        return response;
    }

    private WorkflowEditorDocument newDocument(Workflow workflow, String userId) {
        WorkflowEditorDocument document = new WorkflowEditorDocument();
        document.setWorkflowId(workflow.getWorkflowId());
        document.setProjectId(workflow.getProjectId());
        document.setCreatedBy(userId);
        document.setUpdatedBy(userId);
        document.setDeletedYn(NOT_DELETED);
        document.setVersion(0);
        return document;
    }

    private WorkflowEditorElement newElement(Workflow workflow, String elementId, String userId) {
        WorkflowEditorElement element = new WorkflowEditorElement();
        element.setEditorElementId(generateId());
        element.setWorkflowId(workflow.getWorkflowId());
        element.setProjectId(workflow.getProjectId());
        element.setElementId(elementId.trim());
        element.setCreatedBy(userId);
        element.setUpdatedBy(userId);
        element.setDeletedYn(NOT_DELETED);
        element.setVersion(0);
        return element;
    }

    private void applyElement(
        WorkflowEditorElement element,
        Workflow workflow,
        EditorElementRequest request,
        int fallbackOrder,
        String userId,
        long nonce
    ) {
        element.setWorkflowId(workflow.getWorkflowId());
        element.setProjectId(workflow.getProjectId());
        element.setElementId(request.id().trim());
        element.setElementType(normalizeType(request.type()));
        element.setPosX(request.x());
        element.setPosY(request.y());
        element.setWidth(request.width());
        element.setHeight(request.height());
        element.setRotation(request.rotation() == null ? 0d : request.rotation());
        element.setOpacity(request.opacity() == null ? 1d : request.opacity());
        element.setParentElementId(blankToNull(request.parentId()));
        element.setElementOrder(request.order() == null ? fallbackOrder : request.order());
        element.setGeometryJson(toJsonOrDefault(request.geometry()));
        element.setStyleJson(toJsonOrDefault(request.style()));
        element.setLockedYn(Boolean.TRUE.equals(request.locked()) ? "Y" : "N");
        element.setHiddenYn(Boolean.TRUE.equals(request.hidden()) ? "Y" : "N");
        element.setDeletedYn(NOT_DELETED);
        element.setUpdatedBy(userId);
        element.setVersion(element.getVersion() + 1);
        element.setVersionNonce(nonce);
    }

    private void validateDocument(EditorDocumentSaveRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Editor document request is required.");
        }
        int schemaVersion = request.schemaVersion() == null ? 1 : request.schemaVersion();
        if (schemaVersion != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported editor document schemaVersion.");
        }
        if (request.camera() != null && !request.camera().isObject()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "camera must be a JSON object.");
        }
        if (request.elements() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "elements are required.");
        }

        Map<String, Boolean> ids = new LinkedHashMap<>();
        for (EditorElementRequest element : request.elements()) {
            validateElement(element);
            String id = element.id().trim();
            if (ids.put(id, Boolean.TRUE) != null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Duplicate editor element id.");
            }
        }
    }

    private void validateElement(EditorElementRequest element) {
        if (element == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Editor element is required.");
        }
        if (element.id() == null || element.id().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "element id is required.");
        }
        String type = normalizeType(element.type());
        requireFinite(element.x(), "x");
        requireFinite(element.y(), "y");
        requireFinite(element.width(), "width");
        requireFinite(element.height(), "height");
        if (element.width() < 0 || element.height() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Element size must be non-negative.");
        }
        if (element.rotation() != null) requireFinite(element.rotation(), "rotation");
        if (element.opacity() != null) requireFinite(element.opacity(), "opacity");
        if (element.geometry() != null && !element.geometry().isObject()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "geometry must be a JSON object.");
        }
        if (element.style() != null && !element.style().isObject()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "style must be a JSON object.");
        }

        JsonNode geometry = element.geometry();
        switch (type) {
            case "polygon", "freehand" -> requireArray(geometry, "points", type + " requires geometry.points.");
            case "line" -> {
                requireObject(geometry, "start", "line requires geometry.start.");
                requireObject(geometry, "end", "line requires geometry.end.");
            }
            case "group" -> requireArray(geometry, "childIds", "group requires geometry.childIds.");
            default -> {
            }
        }
    }

    private String normalizeType(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "element type is required.");
        }
        String normalized = value.trim().toLowerCase();
        if (!SUPPORTED_TYPES.contains(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported editor element type.");
        }
        return normalized;
    }

    private void requireFinite(Double value, String field) {
        if (value == null || !Double.isFinite(value)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " must be a finite number.");
        }
    }

    private void requireArray(JsonNode geometry, String key, String message) {
        JsonNode value = geometry == null ? null : geometry.get(key);
        if (value == null || !value.isArray()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
    }

    private void requireObject(JsonNode geometry, String key, String message) {
        JsonNode value = geometry == null ? null : geometry.get(key);
        if (value == null || !value.isObject()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
    }

    private EditorDocumentResponse toDocumentResponse(
        WorkflowEditorDocument document,
        List<WorkflowEditorElement> elements
    ) {
        if (document == null) {
            return new EditorDocumentResponse(
                1,
                readJson("{\"x\":0,\"y\":0,\"zoom\":1}"),
                nextElementSeq(elements),
                0,
                0L,
                elements.stream().map(this::toElementResponse).toList()
            );
        }
        return new EditorDocumentResponse(
            document.getSchemaVersion(),
            readJson(document.getCameraJson()),
            document.getNextElementSeq(),
            document.getVersion(),
            document.getVersionNonce(),
            elements.stream().map(this::toElementResponse).toList()
        );
    }

    private EditorElementResponse toElementResponse(WorkflowEditorElement element) {
        return new EditorElementResponse(
            element.getElementId(),
            element.getElementType(),
            element.getPosX(),
            element.getPosY(),
            element.getWidth(),
            element.getHeight(),
            element.getRotation(),
            element.getOpacity(),
            element.getParentElementId(),
            "Y".equalsIgnoreCase(element.getLockedYn()),
            "Y".equalsIgnoreCase(element.getHiddenYn()),
            element.getElementOrder(),
            readJson(element.getGeometryJson()),
            readJson(element.getStyleJson()),
            element.getVersion(),
            element.getVersionNonce()
        );
    }

    private int nextElementSeq(List<WorkflowEditorElement> elements) {
        return elements.size() + 1;
    }

    private String blankToNull(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String toJsonOrDefault(JsonNode node) {
        return node == null || node.isNull() ? "{}" : node.toString();
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to deserialize editor document JSON.");
        }
    }

    private String generateId() {
        return "ede_" + java.util.UUID.randomUUID().toString().replace("-", "");
    }
}
