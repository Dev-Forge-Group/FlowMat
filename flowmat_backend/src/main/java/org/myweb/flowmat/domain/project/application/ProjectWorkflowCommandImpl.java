package org.myweb.flowmat.domain.project.application;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectWorkflowCommand;
import org.myweb.flowmat.domain.project.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ProjectWorkflowCommandImpl implements ProjectWorkflowCommand {

    private static final String NOT_DELETED = "N";

    private final ProjectRepository projectRepository;

    @Override
    public void workflowAdded(String projectId, String workflowId) {
        projectRepository.findByProjectIdAndDeletedYn(projectId, NOT_DELETED)
            .filter(project -> project.getCurrentWorkflowId() == null)
            .ifPresent(project -> {
                project.setCurrentWorkflowId(workflowId);
                projectRepository.save(project);
            });
    }

    @Override
    public void workflowRemoved(String projectId, String workflowId) {
        projectRepository.findByProjectIdAndDeletedYn(projectId, NOT_DELETED)
            .filter(project -> workflowId.equals(project.getCurrentWorkflowId()))
            .ifPresent(project -> {
                project.setCurrentWorkflowId(null);
                projectRepository.save(project);
            });
    }
}
