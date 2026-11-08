package org.myweb.flowmat.domain.workflow.collab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.myweb.flowmat.domain.workflow.annotation.api.dto.response.CanvasAnnotationResponse;
import org.myweb.flowmat.domain.workflow.annotation.domain.CanvasAnnotation;
import org.myweb.flowmat.domain.workflow.annotation.repository.CanvasAnnotationRepository;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessIoResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowGraphChangesResponse;
import org.myweb.flowmat.domain.workflow.application.WorkflowAccessService;
import org.myweb.flowmat.domain.workflow.application.WorkflowCanvasServiceImpl;
import org.myweb.flowmat.domain.workflow.collab.dto.GraphChangeMessage;
import org.myweb.flowmat.domain.workflow.collab.dto.GraphChangeMessage.Type;
import org.myweb.flowmat.domain.workflow.collab.dto.GraphEntityPayload;
import org.myweb.flowmat.domain.workflow.domain.entity.Process;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessIo;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.myweb.flowmat.domain.workflow.repository.ProcessConnectionRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessIoRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessRepository;
import org.myweb.flowmat.domain.workflow.repository.WorkflowRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.security.AuthUser;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GraphSyncService {

    private static final String NOT_DELETED = "N";

    private final SimpMessagingTemplate messagingTemplate;
    private final RedisGraphChangeStore graphChangeStore;
    private final ProcessRepository processRepository;
    private final ProcessIoRepository processIoRepository;
    private final ProcessConnectionRepository processConnectionRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowAccessService workflowAccess;
    private final CanvasAnnotationRepository canvasAnnotationRepository;
    private final ObjectMapper objectMapper;

    public void broadcast(Type type, String workflowId, String entityId) {
        broadcast(type, workflowId, entityId, resolveCurrentUserId());
    }

    @Transactional
    public void broadcast(Type type, String workflowId, String entityId, String userId) {
        Runnable publish = () -> {
            String topic = "/topic/workflow/" + workflowId + "/graph";
            try {
                GraphEntityPayload payload = loadPayload(type, entityId);
                GraphChangeMessage message = graphChangeStore.append(type, workflowId, entityId, userId, payload);
                messagingTemplate.convertAndSend(topic, message);
            } catch (RuntimeException error) {
                log.error("Committed graph change could not be published for workflowId={}, entityId={}",
                    workflowId, entityId, error);
                try {
                    messagingTemplate.convertAndSend(topic, new GraphChangeMessage(
                        0, "RESET_REQUIRED", workflowId, entityId, userId, System.currentTimeMillis(), null
                    ));
                } catch (RuntimeException fallbackError) {
                    log.error("Graph reset signal could not be published for workflowId={}", workflowId, fallbackError);
                }
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish.run();
            }
        });
    }

    public WorkflowGraphChangesResponse getChangesSince(String workflowId, long sinceSeq) {
        workflowAccess.requireWorkflowReadAccess(workflowId);
        return graphChangeStore.getChangesSince(workflowId, sinceSeq);
    }

    public long getCurrentSeq(String workflowId) {
        return graphChangeStore.getCurrentSeq(workflowId);
    }

    private GraphEntityPayload loadPayload(Type type, String entityId) {
        return switch (type) {
            case WORKFLOW_UPDATED -> workflowRepository.findByWorkflowIdAndDeletedYn(entityId, NOT_DELETED)
                .map(this::toWorkflowPayload)
                .orElse(null);
            case NODE_CREATED, NODE_UPDATED -> processRepository.findByProcessIdAndDeletedYn(entityId, NOT_DELETED)
                .map(this::toNodePayload)
                .orElse(null);
            case PORT_CREATED, PORT_UPDATED, PORT_DELETED -> processIoRepository.findById(entityId)
                .map(ProcessIo::getProcessId)
                .flatMap(processId -> processRepository.findByProcessIdAndDeletedYn(processId, NOT_DELETED))
                .map(this::toNodePayload)
                .orElse(null);
            case CONNECTION_CREATED, CONNECTION_UPDATED -> processConnectionRepository
                .findByConnectionIdAndDeletedYn(entityId, NOT_DELETED)
                .map(WorkflowCanvasServiceImpl::toConnectionResponse)
                .map(connection -> new GraphEntityPayload(null, null, List.of(), connection, null))
                .orElse(null);
            case ANNOTATION_CREATED, ANNOTATION_UPDATED -> canvasAnnotationRepository
                .findByAnnotationIdAndDeletedYn(entityId, NOT_DELETED)
                .map(this::toAnnotationResponse)
                .map(annotation -> new GraphEntityPayload(null, null, List.of(), null, annotation))
                .orElse(null);
            case NODE_DELETED, CONNECTION_DELETED, ANNOTATION_DELETED -> null;
            case EDITOR_DOCUMENT_UPDATED -> null;
        };
    }

    private GraphEntityPayload toWorkflowPayload(Workflow workflow) {
        return new GraphEntityPayload(
            WorkflowCanvasServiceImpl.toWorkflowResponse(workflow),
            null,
            List.of(),
            null,
            null
        );
    }

    private GraphEntityPayload toNodePayload(Process process) {
        List<ProcessIoResponse> processIos = processIoRepository
            .findAllByProcessIdAndDeletedYnOrderByCreatedAtAsc(process.getProcessId(), NOT_DELETED)
            .stream()
            .map(WorkflowCanvasServiceImpl::toProcessIoResponse)
            .toList();
        ProcessResponse processResponse = WorkflowCanvasServiceImpl.toProcessResponse(process);
        return new GraphEntityPayload(null, processResponse, processIos, null, null);
    }

    private CanvasAnnotationResponse toAnnotationResponse(CanvasAnnotation annotation) {
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

    private static String resolveCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof AuthUser authUser) {
            return authUser.getUserId();
        }
        return authentication.getName();
    }
}
