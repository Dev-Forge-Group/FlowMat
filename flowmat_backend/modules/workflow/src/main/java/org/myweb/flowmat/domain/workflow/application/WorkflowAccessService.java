package org.myweb.flowmat.domain.workflow.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowAccess;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowAccessView;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Access checks on a workflow and the processes, ports and connections in it
 * (docs/architecture/adr/ADR-012-workflow-access-boundary.md): finds the object, then checks its project through
 * {@link ProjectAccess}. Moved here from ProjectAccess unchanged, so the 401, 403 and 404 answers stay.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowAccessService implements WorkflowAccess {

    private static final String NOT_DELETED = "N";

    private final ProjectAccess projectAccessService;
    private final WorkflowRepository workflowRepository;
    private final ProcessRepository processRepository;
    private final ProcessIoRepository processIoRepository;
    private final ProcessConnectionRepository processConnectionRepository;
    private final EntityManager entityManager;

    public Workflow requireWorkflowReadAccess(String workflowId) {
        return requireWorkflowReadAccess(workflowId, projectAccessService.requireCurrentUserId());
    }

    public Workflow requireWorkflowReadAccess(String workflowId, String userId) {
        Workflow workflow = workflowRepository.findByWorkflowIdAndDeletedYn(workflowId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectReadAccess(workflow.getProjectId(), userId);
        return workflow;
    }

    public Workflow requireWorkflowWriteAccess(String workflowId) {
        return requireWorkflowWriteAccess(workflowId, projectAccessService.requireCurrentUserId());
    }

    public Workflow requireWorkflowWriteAccess(String workflowId, String userId) {
        Workflow workflow = workflowRepository.findByWorkflowIdAndDeletedYn(workflowId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectWriteAccess(workflow.getProjectId(), userId);
        return workflow;
    }

    public Process requireProcessReadAccess(String processId) {
        Process process = processRepository.findByProcessIdAndDeletedYn(processId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectReadAccess(process.getProjectId(), projectAccessService.requireCurrentUserId());
        return process;
    }

    public Process requireProcessWriteAccess(String processId) {
        Process process = processRepository.findByProcessIdAndDeletedYn(processId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectWriteAccess(process.getProjectId(), projectAccessService.requireCurrentUserId());
        return process;
    }

    public ProcessIo requireProcessIoReadAccess(String processIoId) {
        ProcessIo processIo = processIoRepository.findByProcessIoIdAndDeletedYn(processIoId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        requireProcessReadAccess(processIo.getProcessId());
        return processIo;
    }

    public ProcessIo requireProcessIoWriteAccess(String processIoId) {
        ProcessIo processIo = processIoRepository.findByProcessIoIdAndDeletedYn(processIoId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        requireProcessWriteAccess(processIo.getProcessId());
        return processIo;
    }

    public ProcessConnection requireConnectionReadAccess(String connectionId) {
        ProcessConnection connection = processConnectionRepository.findByConnectionIdAndDeletedYn(connectionId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectReadAccess(connection.getProjectId(), projectAccessService.requireCurrentUserId());
        return connection;
    }

    public ProcessConnection requireConnectionWriteAccess(String connectionId) {
        ProcessConnection connection = processConnectionRepository.findByConnectionIdAndDeletedYn(connectionId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectWriteAccess(connection.getProjectId(), projectAccessService.requireCurrentUserId());
        return connection;
    }

    @Override
    public WorkflowAccessView requireRead(String workflowId) {
        return view(requireWorkflowReadAccess(workflowId));
    }

    @Override
    public WorkflowAccessView requireRead(String workflowId, String userId) {
        return view(requireWorkflowReadAccess(workflowId, userId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public WorkflowAccessView requireWriteAndLock(String workflowId) {
        Workflow workflow = requireWorkflowWriteAccess(workflowId);
        entityManager.lock(workflow, LockModeType.PESSIMISTIC_WRITE);
        return view(workflow);
    }

    @Override
    public WorkflowAccessView requireWrite(String workflowId, String userId) {
        return view(requireWorkflowWriteAccess(workflowId, userId));
    }

    private static WorkflowAccessView view(Workflow workflow) {
        return new WorkflowAccessView(workflow.getWorkflowId(), workflow.getProjectId());
    }
}
