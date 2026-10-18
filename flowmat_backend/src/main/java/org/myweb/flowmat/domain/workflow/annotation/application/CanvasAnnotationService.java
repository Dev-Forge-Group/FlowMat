package org.myweb.flowmat.domain.workflow.annotation.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.workflow.annotation.api.dto.request.CanvasAnnotationBatchRequest;
import org.myweb.flowmat.domain.workflow.annotation.api.dto.request.CanvasAnnotationCreateRequest;
import org.myweb.flowmat.domain.workflow.annotation.api.dto.request.CanvasAnnotationPatchRequest;
import org.myweb.flowmat.domain.workflow.annotation.api.dto.response.CanvasAnnotationResponse;
import org.myweb.flowmat.domain.workflow.annotation.domain.AnnotationType;
import org.myweb.flowmat.domain.workflow.annotation.domain.CanvasAnnotation;
import org.myweb.flowmat.domain.workflow.annotation.domain.ShapeKind;
import org.myweb.flowmat.domain.workflow.annotation.repository.CanvasAnnotationRepository;
import org.myweb.flowmat.domain.workflow.application.WorkflowAccessService;
import org.myweb.flowmat.domain.workflow.collab.GraphSyncService;
import org.myweb.flowmat.domain.workflow.collab.dto.GraphChangeMessage;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class CanvasAnnotationService {

    private static final String NOT_DELETED = "N";

    private final CanvasAnnotationRepository canvasAnnotationRepository;
    private final ProjectAccessService projectAccessService;
    private final WorkflowAccessService workflowAccess;
    private final FractionalIndexService fractionalIndexService;
    private final CanvasAnnotationReconcileService reconcileService;
    private final GraphSyncService graphSyncService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<CanvasAnnotationResponse> list(String workflowId) {
        Workflow workflow = workflowAccess.requireWorkflowReadAccess(workflowId);
        return canvasAnnotationRepository.findAllByWorkflowIdAndDeletedYnOrderByZIndexAscCreatedAtAsc(
                workflow.getWorkflowId(),
                NOT_DELETED
            ).stream()
            .map(this::toResponse)
            .toList();
    }

    public CanvasAnnotationResponse create(String workflowId, CanvasAnnotationCreateRequest request) {
        Workflow workflow = workflowAccess.requireWorkflowWriteAccess(workflowId);
        CanvasAnnotation annotation = new CanvasAnnotation();
        annotation.setAnnotationId(generateId());
        annotation.setWorkflowId(workflow.getWorkflowId());
        annotation.setProjectId(workflow.getProjectId());
        annotation.setAnnotationType(parseAnnotationType(request.annotationType()).name().toLowerCase());
        annotation.setShapeKind(request.shapeKind() == null ? null : parseShapeKind(request.shapeKind()).name().toLowerCase());
        annotation.setPosX(request.posX());
        annotation.setPosY(request.posY());
        annotation.setWidth(request.width());
        annotation.setHeight(request.height());
        annotation.setRotation(request.rotation() == null ? 0d : request.rotation());
        annotation.setPointsJson(toJson(request.points()));
        annotation.setTextContent(blankToNull(request.textContent()));
        annotation.setStyleJson(toJsonOrDefault(request.style()));
        annotation.setZIndex(resolveCreateZIndex(workflowId, request.zIndex()));
        annotation.setGroupId(blankToNull(request.groupId()));
        annotation.setCreatedBy(projectAccessService.requireCurrentUserId());
        annotation.setUpdatedBy(annotation.getCreatedBy());
        annotation.setVersion(1);
        annotation.setVersionNonce(System.currentTimeMillis());
        validateShapeFields(annotation);
        canvasAnnotationRepository.save(annotation);
        graphSyncService.broadcast(GraphChangeMessage.Type.ANNOTATION_CREATED, workflowId, annotation.getAnnotationId());
        return toResponse(annotation);
    }

    public CanvasAnnotationResponse patch(String workflowId, String annotationId, CanvasAnnotationPatchRequest request) {
        workflowAccess.requireWorkflowWriteAccess(workflowId);
        CanvasAnnotation annotation = canvasAnnotationRepository.findByAnnotationIdAndDeletedYn(annotationId, NOT_DELETED)
            .filter(item -> workflowId.equals(item.getWorkflowId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        if (reconcileService.shouldDiscardIncoming(annotation, request.version(), request.versionNonce())) {
            return toResponse(annotation);
        }

        if (request.posX() != null) annotation.setPosX(request.posX());
        if (request.posY() != null) annotation.setPosY(request.posY());
        if (request.width() != null) annotation.setWidth(request.width());
        if (request.height() != null) annotation.setHeight(request.height());
        if (request.rotation() != null) annotation.setRotation(request.rotation());
        if (request.points() != null) annotation.setPointsJson(toJson(request.points()));
        if (request.textContent() != null) annotation.setTextContent(blankToNull(request.textContent()));
        if (request.style() != null) annotation.setStyleJson(toJsonOrDefault(request.style()));
        if (request.zIndex() != null && !request.zIndex().isBlank()) annotation.setZIndex(request.zIndex().trim());
        if (request.groupId() != null) annotation.setGroupId(blankToNull(request.groupId()));
        if (request.lockedYn() != null && !request.lockedYn().isBlank()) annotation.setLockedYn(request.lockedYn().trim().toUpperCase());
        annotation.setVersion(annotation.getVersion() + 1);
        annotation.setVersionNonce(System.currentTimeMillis());
        annotation.setUpdatedBy(projectAccessService.requireCurrentUserId());
        validateShapeFields(annotation);
        graphSyncService.broadcast(GraphChangeMessage.Type.ANNOTATION_UPDATED, workflowId, annotation.getAnnotationId());
        return toResponse(annotation);
    }

    public void delete(String workflowId, String annotationId) {
        workflowAccess.requireWorkflowWriteAccess(workflowId);
        CanvasAnnotation annotation = canvasAnnotationRepository.findByAnnotationIdAndDeletedYn(annotationId, NOT_DELETED)
            .filter(item -> workflowId.equals(item.getWorkflowId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        annotation.setDeletedYn("Y");
        annotation.setUpdatedBy(projectAccessService.requireCurrentUserId());
        annotation.setVersion(annotation.getVersion() + 1);
        annotation.setVersionNonce(System.currentTimeMillis());
        graphSyncService.broadcast(GraphChangeMessage.Type.ANNOTATION_DELETED, workflowId, annotationId);
    }

    public List<CanvasAnnotationResponse> batchUpdate(String workflowId, CanvasAnnotationBatchRequest request) {
        workflowAccess.requireWorkflowWriteAccess(workflowId);
        String userId = projectAccessService.requireCurrentUserId();
        for (CanvasAnnotationBatchRequest.CanvasAnnotationBatchItemRequest item : request.items()) {
            CanvasAnnotation annotation = canvasAnnotationRepository.findByAnnotationIdAndDeletedYn(item.annotationId(), NOT_DELETED)
                .filter(existing -> workflowId.equals(existing.getWorkflowId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
            if (item.posX() != null) annotation.setPosX(item.posX());
            if (item.posY() != null) annotation.setPosY(item.posY());
            if (item.width() != null) annotation.setWidth(item.width());
            if (item.height() != null) annotation.setHeight(item.height());
            if (item.rotation() != null) annotation.setRotation(item.rotation());
            if (item.zIndex() != null && !item.zIndex().isBlank()) annotation.setZIndex(item.zIndex().trim());
            if (item.groupId() != null) annotation.setGroupId(blankToNull(item.groupId()));
            annotation.setVersion(annotation.getVersion() + 1);
            annotation.setVersionNonce(System.currentTimeMillis());
            annotation.setUpdatedBy(userId);
            validateShapeFields(annotation);
            graphSyncService.broadcast(GraphChangeMessage.Type.ANNOTATION_UPDATED, workflowId, annotation.getAnnotationId(), userId);
        }
        return list(workflowId);
    }

    public CanvasAnnotationResponse toResponse(CanvasAnnotation annotation) {
        return new CanvasAnnotationResponse(
            annotation.getAnnotationId(),
            annotation.getWorkflowId(),
            annotation.getProjectId(),
            annotation.getAnnotationType(),
            annotation.getShapeKind(),
            annotation.getPosX(),
            annotation.getPosY(),
            annotation.getWidth(),
            annotation.getHeight(),
            annotation.getRotation(),
            readJson(annotation.getPointsJson()),
            annotation.getTextContent(),
            readJson(annotation.getStyleJson()),
            annotation.getZIndex(),
            annotation.getGroupId(),
            annotation.getLockedYn(),
            annotation.getVersion(),
            annotation.getVersionNonce()
        );
    }

    private String resolveCreateZIndex(String workflowId, String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        List<CanvasAnnotation> existing = canvasAnnotationRepository.findAllByWorkflowIdAndDeletedYnOrderByZIndexAscCreatedAtAsc(workflowId, NOT_DELETED);
        String last = existing.isEmpty() ? null : existing.get(existing.size() - 1).getZIndex();
        return fractionalIndexService.between(last, null);
    }

    private void validateShapeFields(CanvasAnnotation annotation) {
        AnnotationType type = parseAnnotationType(annotation.getAnnotationType());
        if (type == AnnotationType.SHAPE && annotation.getShapeKind() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Shape annotations require shapeKind.");
        }
        if (type == AnnotationType.FREEHAND && (annotation.getPointsJson() == null || annotation.getPointsJson().isBlank())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Freehand annotations require points.");
        }
        if (type == AnnotationType.TEXT && (annotation.getTextContent() == null || annotation.getTextContent().isBlank())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Text annotations require textContent.");
        }
    }

    private AnnotationType parseAnnotationType(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "annotationType is required.");
        }
        try {
            return AnnotationType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported annotationType.");
        }
    }

    private ShapeKind parseShapeKind(String value) {
        try {
            return ShapeKind.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported shapeKind.");
        }
    }

    private String generateId() {
        return "ann_" + java.util.UUID.randomUUID().toString().replace("-", "");
    }

    private String blankToNull(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String toJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.toString();
    }

    private String toJsonOrDefault(JsonNode node) {
        return node == null || node.isNull() ? "{}" : node.toString();
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to deserialize annotation JSON.");
        }
    }
}
