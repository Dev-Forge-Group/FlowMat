package org.myweb.flowmat.domain.workflow.application;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.workflow.annotation.api.dto.response.CanvasAnnotationResponse;
import org.myweb.flowmat.domain.workflow.annotation.application.CanvasAnnotationService;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessConnectionResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessIoResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowCanvasResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowResponse;
import org.myweb.flowmat.domain.workflow.collab.GraphSyncService;
import org.myweb.flowmat.domain.workflow.domain.entity.Process;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessConnection;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessIo;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.myweb.flowmat.domain.workflow.repository.ProcessConnectionRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessIoRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessRepository;
import org.myweb.flowmat.domain.workflow.repository.WorkflowRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowCanvasServiceImpl implements WorkflowCanvasService {

    private static final String NOT_DELETED = "N";

    private final WorkflowRepository workflowRepository;
    private final ProcessRepository processRepository;
    private final ProcessIoRepository processIoRepository;
    private final ProcessConnectionRepository processConnectionRepository;
    private final GraphSyncService graphSyncService;
    private final ProjectAccessService projectAccessService;
    private final WorkflowAccessService workflowAccess;
    private final CanvasAnnotationService canvasAnnotationService;

    @Override
    public WorkflowCanvasResponse getCanvas(String workflowId) {
        Workflow workflow = workflowAccess.requireWorkflowReadAccess(workflowId);
        // Capture the replay cursor before reading the DB snapshot. Changes committed
        // during the reads are then replayed, rather than silently skipped.
        long graphSeq = graphSyncService.getCurrentSeq(workflow.getWorkflowId());

        List<Process> processes = processRepository.findAllByWorkflowIdAndDeletedYnOrderByCreatedAtAsc(
            workflow.getWorkflowId(),
            NOT_DELETED
        );
        List<String> processIds = processes.stream().map(Process::getProcessId).toList();
        List<ProcessIo> processIos = processIds.isEmpty()
            ? List.of()
            : processIoRepository.findAllByProcessIdInAndDeletedYnOrderByCreatedAtAsc(processIds, NOT_DELETED);
        List<ProcessConnection> connections = processConnectionRepository
            .findAllByWorkflowIdAndDeletedYnOrderByCreatedAtAsc(workflow.getWorkflowId(), NOT_DELETED);
        List<CanvasAnnotationResponse> annotations = canvasAnnotationService.list(workflow.getWorkflowId());
        String currentUserRole = projectAccessService.resolveCurrentUserRole(workflow.getProjectId());

        return new WorkflowCanvasResponse(
            toWorkflowResponse(workflow),
            graphSeq,
            processes.stream().map(WorkflowCanvasServiceImpl::toProcessResponse).toList(),
            processIos.stream().map(WorkflowCanvasServiceImpl::toProcessIoResponse).toList(),
            connections.stream().map(WorkflowCanvasServiceImpl::toConnectionResponse).toList(),
            annotations,
            currentUserRole
        );
    }

    public static WorkflowResponse toWorkflowResponse(Workflow workflow) {
        return new WorkflowResponse(
            workflow.getWorkflowId(),
            workflow.getProjectId(),
            workflow.getWorkflowName(),
            workflow.getWorkflowDesc(),
            workflow.getWorkflowType(),
            workflow.getWorkflowStatus()
        );
    }

    public static ProcessResponse toProcessResponse(Process process) {
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

    public static ProcessIoResponse toProcessIoResponse(ProcessIo processIo) {
        return ProcessIoResponse.from(processIo);
    }

    public static ProcessConnectionResponse toConnectionResponse(ProcessConnection connection) {
        return new ProcessConnectionResponse(
            connection.getConnectionId(),
            connection.getProjectId(),
            connection.getWorkflowId(),
            connection.getFromProcessId(),
            connection.getToProcessId(),
            connection.getFromIoId(),
            connection.getToIoId(),
            connection.getItemId(),
            connection.getSourceHandle(),
            connection.getTargetHandle(),
            connection.getConnectionType(),
            connection.getConnectionLabel(),
            connection.getFlowRate(),
            connection.getUnit(),
            connection.getDelayTimeSec(),
            connection.getDelayBasis(),
            connection.getLossRate(),
            connection.getPriority(),
            connection.getConditionExpr(),
            connection.getCapacity(),
            connection.getFailurePolicy(),
            connection.isDefaultRoute(),
            connection.getSplitKind(),
            connection.getSplitValue(),
            connection.getVersion(),
            connection.getVersionNonce()
        );
    }
}
