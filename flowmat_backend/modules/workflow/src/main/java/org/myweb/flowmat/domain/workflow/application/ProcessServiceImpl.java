package org.myweb.flowmat.domain.workflow.application;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessCreateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessPositionRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessUpdateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessResponse;
import org.myweb.flowmat.domain.workflow.domain.entity.Process;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.myweb.flowmat.domain.workflow.domain.enums.NodeType;
import org.myweb.flowmat.domain.workflow.collab.GraphSyncService;
import org.myweb.flowmat.domain.workflow.collab.dto.GraphChangeMessage.Type;
import org.myweb.flowmat.domain.workflow.repository.ProcessRepository;
import org.myweb.flowmat.domain.workflow.repository.WorkflowRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProcessServiceImpl implements ProcessService {

    private static final String NOT_DELETED = "N";
    private static final String DELETED = "Y";
    private static final String DEFAULT_COLOR_SCHEME = "slate";

    private final ProcessRepository processRepository;
    private final WorkflowRepository workflowRepository;
    private final IdGenerator idGenerator;
    private final GraphSyncService graphSyncService;
    private final WorkflowAccessService workflowAccess;

    @Override
    public List<ProcessResponse> listProcesses(String workflowId) {
        workflowAccess.requireWorkflowReadAccess(workflowId);
        return processRepository.findAllByWorkflowIdAndDeletedYnOrderByCreatedAtAsc(workflowId, NOT_DELETED).stream()
            .map(ProcessServiceImpl::toResponse)
            .toList();
    }

    @Override
    @Transactional
    public ProcessResponse createProcess(ProcessCreateRequest request) {
        Workflow workflow = workflowAccess.requireWorkflowWriteAccess(request.workflowId());

        Process process = new Process();
        process.setProcessId(idGenerator.generate());
        process.setProjectId(workflow.getProjectId());
        process.setWorkflowId(workflow.getWorkflowId());
        process.setProcessName(request.processName().trim());
        process.setProcessType(defaultIfBlank(request.processType(), "generic"));
        process.setNodeType(normalizeNodeType(request.nodeType()));
        process.setProcessStatus("active");
        process.setColorScheme(defaultColorScheme(request.colorScheme(), DEFAULT_COLOR_SCHEME));
        process.setPosX(defaultIfNull(request.posX(), 0.0));
        process.setPosY(defaultIfNull(request.posY(), 0.0));
        process.setWidth(defaultIfNull(request.width(), 120.0));
        process.setHeight(defaultIfNull(request.height(), 60.0));
        process.setProcessDesc(trimToNull(request.processDesc()));
        process.setDeletedYn(NOT_DELETED);
        process.setVersion(1);
        process.setVersionNonce(ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE));
        ProcessResponse response = toResponse(processRepository.save(process));
        graphSyncService.broadcast(Type.NODE_CREATED, response.workflowId(), response.processId());
        return response;
    }

    @Override
    public ProcessResponse getProcess(String processId) {
        return toResponse(workflowAccess.requireProcessReadAccess(processId));
    }

    @Override
    @Transactional
    public ProcessResponse updateProcess(String processId, ProcessUpdateRequest request) {
        Process process = workflowAccess.requireProcessWriteAccess(processId);
        if (hasText(request.processName())) {
            process.setProcessName(request.processName().trim());
        }
        if (hasText(request.processType())) {
            process.setProcessType(request.processType().trim().toLowerCase());
        }
        if (hasText(request.nodeType())) {
            process.setNodeType(normalizeNodeType(request.nodeType()));
        }
        if (hasText(request.processStatus())) {
            process.setProcessStatus(request.processStatus().trim().toLowerCase());
        }
        if (request.colorScheme() != null) {
            process.setColorScheme(defaultColorScheme(request.colorScheme(), process.getColorScheme()));
        }
        if (request.posX() != null) {
            process.setPosX(request.posX());
        }
        if (request.posY() != null) {
            process.setPosY(request.posY());
        }
        if (request.width() != null) {
            process.setWidth(request.width());
        }
        if (request.height() != null) {
            process.setHeight(request.height());
        }
        if (request.processDesc() != null) {
            process.setProcessDesc(trimToNull(request.processDesc()));
        }
        process.setVersion(process.getVersion() + 1);
        process.setVersionNonce(ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE));
        ProcessResponse response = toResponse(processRepository.save(process));
        graphSyncService.broadcast(Type.NODE_UPDATED, response.workflowId(), response.processId());
        return response;
    }

    @Override
    @Transactional
    public ProcessResponse updatePosition(String processId, ProcessPositionRequest request) {
        Process process = workflowAccess.requireProcessWriteAccess(processId);
        process.setPosX(request.posX());
        process.setPosY(request.posY());
        process.setVersion(process.getVersion() + 1);
        process.setVersionNonce(ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE));
        ProcessResponse response = toResponse(processRepository.save(process));
        graphSyncService.broadcast(Type.NODE_UPDATED, response.workflowId(), processId);
        return response;
    }

    @Override
    @Transactional
    public void deleteProcess(String processId) {
        Process process = workflowAccess.requireProcessWriteAccess(processId);
        String workflowId = process.getWorkflowId();
        process.setDeletedYn(DELETED);
        process.setProcessStatus("deleted");
        processRepository.save(process);
        graphSyncService.broadcast(Type.NODE_DELETED, workflowId, processId);
    }

    private static ProcessResponse toResponse(Process process) {
        return new ProcessResponse(
            process.getProcessId(),
            process.getProjectId(),
            process.getWorkflowId(),
            process.getProcessName(),
            process.getProcessType(),
            process.getNodeType(),
            process.getProcessStatus(),
            process.getColorScheme(),
            process.getPosX(),
            process.getPosY(),
            process.getWidth(),
            process.getHeight(),
            process.getProcessDesc(),
            process.getVersion(),
            process.getVersionNonce()
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return hasText(value) ? value.trim().toLowerCase() : defaultValue;
    }

    private static Double defaultIfNull(Double value, Double defaultValue) {
        return value != null ? value : defaultValue;
    }

    static String defaultColorScheme(String value, String defaultValue) {
        return hasText(value) ? value.trim().toLowerCase() : defaultValue;
    }

    private static String normalizeNodeType(String value) {
        try {
            return NodeType.normalize(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }
}
