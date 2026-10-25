package org.myweb.flowmat.domain.workflow.application;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectWorkflowCommand;
import org.myweb.flowmat.domain.workflow.api.dto.request.WorkflowTemplateApplyRequest;
import org.myweb.flowmat.global.rbac.PermissionService;
import org.myweb.flowmat.global.rbac.SystemPermission;
import org.myweb.flowmat.domain.workflow.api.dto.request.WorkflowTemplateCreateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.WorkflowTemplateUpdateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowTemplateResponse;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.myweb.flowmat.domain.workflow.domain.entity.WorkflowTemplate;
import org.myweb.flowmat.domain.workflow.repository.WorkflowRepository;
import org.myweb.flowmat.domain.workflow.repository.WorkflowTemplateRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowTemplateServiceImpl implements WorkflowTemplateService {

    private static final String NOT_DELETED = "N";
    private static final String PUBLIC_YES = "Y";

    private final WorkflowTemplateRepository workflowTemplateRepository;
    private final WorkflowRepository workflowRepository;
    private final ProjectWorkflowCommand projectWorkflows;
    private final IdGenerator idGenerator;
    private final ProjectAccessService projectAccessService;
    private final PermissionService permissionService;

    @Override
    public List<WorkflowTemplateResponse> listTemplates() {
        List<WorkflowTemplate> templates = canReadPrivate()
            ? workflowTemplateRepository.findAllByOrderBySortOrderAscCreatedAtAsc()
            : workflowTemplateRepository.findAllByPublicYnOrderBySortOrderAscCreatedAtAsc(PUBLIC_YES);
        return templates.stream()
            .map(WorkflowTemplateServiceImpl::toResponse)
            .toList();
    }

    @Override
    @Transactional
    public WorkflowTemplateResponse createTemplate(WorkflowTemplateCreateRequest request) {
        permissionService.require(SystemPermission.TEMPLATE_MANAGE);
        WorkflowTemplate template = new WorkflowTemplate();
        template.setTemplateId(idGenerator.generate());
        applyTemplateFields(
            template,
            request.templateName(),
            request.templateCategory(),
            request.templateType(),
            request.iconKey(),
            request.defaultWidth(),
            request.defaultHeight(),
            request.defaultDesc(),
            request.defaultConfig(),
            request.publicYn(),
            request.sortOrder()
        );
        return toResponse(workflowTemplateRepository.save(template));
    }

    @Override
    public WorkflowTemplateResponse getTemplate(String templateId) {
        return toResponse(findReadableTemplate(templateId));
    }

    @Override
    @Transactional
    public WorkflowTemplateResponse updateTemplate(String templateId, WorkflowTemplateUpdateRequest request) {
        permissionService.require(SystemPermission.TEMPLATE_MANAGE);
        WorkflowTemplate template = findTemplate(templateId);
        if (hasText(request.templateName())) {
            template.setTemplateName(request.templateName().trim());
        }
        if (hasText(request.templateCategory())) {
            template.setTemplateCategory(request.templateCategory().trim().toLowerCase());
        }
        if (hasText(request.templateType())) {
            template.setTemplateType(request.templateType().trim().toLowerCase());
        }
        if (request.iconKey() != null) {
            template.setIconKey(trimToNull(request.iconKey()));
        }
        if (request.defaultWidth() != null) {
            template.setDefaultWidth(request.defaultWidth());
        }
        if (request.defaultHeight() != null) {
            template.setDefaultHeight(request.defaultHeight());
        }
        if (request.defaultDesc() != null) {
            template.setDefaultDesc(trimToNull(request.defaultDesc()));
        }
        if (request.defaultConfig() != null) {
            template.setDefaultConfig(defaultJson(request.defaultConfig()));
        }
        if (request.publicYn() != null) {
            template.setPublicYn(normalizeYn(request.publicYn()));
        }
        if (request.sortOrder() != null) {
            template.setSortOrder(request.sortOrder());
        }
        return toResponse(workflowTemplateRepository.save(template));
    }

    @Override
    @Transactional
    public void deleteTemplate(String templateId) {
        permissionService.require(SystemPermission.TEMPLATE_MANAGE);
        workflowTemplateRepository.delete(findTemplate(templateId));
    }

    @Override
    @Transactional
    public WorkflowResponse applyTemplate(String templateId, WorkflowTemplateApplyRequest request) {
        WorkflowTemplate template = findReadableTemplate(templateId);
        projectAccessService.requireProjectWriteAccess(request.projectId());
        // The check found the project by this exact id (docs/architecture/adr/ADR-018-entity-boundary.md R2).
        String projectId = request.projectId();

        Workflow workflow = new Workflow();
        workflow.setWorkflowId(idGenerator.generate());
        workflow.setProjectId(projectId);
        workflow.setWorkflowName(defaultText(request.workflowName(), template.getTemplateName()));
        workflow.setWorkflowDesc(template.getDefaultDesc());
        workflow.setWorkflowType(defaultIfBlank(template.getTemplateType(), "main"));
        workflow.setWorkflowStatus("active");
        workflow.setCanvasSnapshot("{}");
        workflow.setSimulationConfig(defaultJson(template.getDefaultConfig()));
        workflow.setLockedYn("N");
        workflow.setDeletedYn(NOT_DELETED);

        Workflow savedWorkflow = workflowRepository.save(workflow);
        projectWorkflows.workflowAdded(projectId, savedWorkflow.getWorkflowId());
        return toWorkflowResponse(savedWorkflow);
    }

    private WorkflowTemplate findTemplate(String templateId) {
        return workflowTemplateRepository.findById(templateId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private WorkflowTemplate findReadableTemplate(String templateId) {
        WorkflowTemplate template = findTemplate(templateId);
        if (!canReadPrivate() && !PUBLIC_YES.equalsIgnoreCase(template.getPublicYn())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Private workflow templates require admin access.");
        }
        return template;
    }

    private boolean canReadPrivate() {
        return permissionService.hasPermission(SystemPermission.TEMPLATE_READ_PRIVATE);
    }

    private static void applyTemplateFields(
        WorkflowTemplate template,
        String templateName,
        String templateCategory,
        String templateType,
        String iconKey,
        Double defaultWidth,
        Double defaultHeight,
        String defaultDesc,
        String defaultConfig,
        String publicYn,
        Integer sortOrder
    ) {
        template.setTemplateName(templateName.trim());
        template.setTemplateCategory(templateCategory.trim().toLowerCase());
        template.setTemplateType(defaultIfBlank(templateType, "main"));
        template.setIconKey(trimToNull(iconKey));
        template.setDefaultWidth(defaultIfNull(defaultWidth, 120.0));
        template.setDefaultHeight(defaultIfNull(defaultHeight, 60.0));
        template.setDefaultDesc(trimToNull(defaultDesc));
        template.setDefaultConfig(defaultJson(defaultConfig));
        template.setPublicYn(normalizeYn(publicYn));
        template.setSortOrder(sortOrder != null ? sortOrder : 0);
    }

    private static WorkflowTemplateResponse toResponse(WorkflowTemplate template) {
        return new WorkflowTemplateResponse(
            template.getTemplateId(),
            template.getTemplateName(),
            template.getTemplateCategory(),
            template.getTemplateType(),
            template.getIconKey(),
            template.getDefaultWidth(),
            template.getDefaultHeight(),
            template.getDefaultDesc(),
            template.getDefaultConfig(),
            template.getPublicYn(),
            template.getSortOrder()
        );
    }

    private static WorkflowResponse toWorkflowResponse(Workflow workflow) {
        return new WorkflowResponse(
            workflow.getWorkflowId(),
            workflow.getProjectId(),
            workflow.getWorkflowName(),
            workflow.getWorkflowDesc(),
            workflow.getWorkflowType(),
            workflow.getWorkflowStatus()
        );
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return hasText(value) ? value.trim().toLowerCase() : defaultValue;
    }

    private static String defaultText(String value, String defaultValue) {
        return hasText(value) ? value.trim() : defaultValue;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static Double defaultIfNull(Double value, Double defaultValue) {
        return value != null ? value : defaultValue;
    }

    private static String defaultJson(String value) {
        return hasText(value) ? value.trim() : "{}";
    }

    private static String normalizeYn(String value) {
        return "N".equalsIgnoreCase(value) ? "N" : "Y";
    }
}
