package org.myweb.flowmat.domain.workflow.application;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessTemplateApplyRequest;
import org.myweb.flowmat.global.rbac.PermissionService;
import org.myweb.flowmat.global.rbac.SystemPermission;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessTemplateCreateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessTemplateUpdateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessTemplateResponse;
import org.myweb.flowmat.domain.workflow.domain.entity.Process;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessTemplate;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.myweb.flowmat.domain.workflow.domain.enums.NodeType;
import org.myweb.flowmat.domain.workflow.repository.ProcessRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessTemplateRepository;
import org.myweb.flowmat.domain.workflow.repository.WorkflowRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProcessTemplateServiceImpl implements ProcessTemplateService {

    private static final String NOT_DELETED = "N";
    private static final String PUBLIC_YES = "Y";
    private static final String DEFAULT_COLOR_SCHEME = "slate";

    private final ProcessTemplateRepository processTemplateRepository;
    private final WorkflowRepository workflowRepository;
    private final ProcessRepository processRepository;
    private final IdGenerator idGenerator;
    private final WorkflowAccessService workflowAccess;
    private final PermissionService permissionService;

    @Override
    public List<ProcessTemplateResponse> listTemplates() {
        List<ProcessTemplate> templates = canReadPrivate()
            ? processTemplateRepository.findAllByOrderBySortOrderAscCreatedAtAsc()
            : processTemplateRepository.findAllByPublicYnOrderBySortOrderAscCreatedAtAsc(PUBLIC_YES);
        return templates.stream()
            .map(ProcessTemplateServiceImpl::toResponse)
            .toList();
    }

    @Override
    @Transactional
    public ProcessTemplateResponse createTemplate(ProcessTemplateCreateRequest request) {
        permissionService.require(SystemPermission.TEMPLATE_MANAGE);
        ProcessTemplate template = new ProcessTemplate();
        template.setTemplateId(idGenerator.generate());
        applyTemplateFields(
            template,
            request.templateName(),
            request.templateCategory(),
            request.templateType(),
            request.iconKey(),
            request.defaultColorScheme(),
            request.defaultWidth(),
            request.defaultHeight(),
            request.defaultDesc(),
            request.defaultConfig(),
            request.publicYn(),
            request.sortOrder()
        );
        return toResponse(processTemplateRepository.save(template));
    }

    @Override
    public ProcessTemplateResponse getTemplate(String templateId) {
        return toResponse(findReadableTemplate(templateId));
    }

    @Override
    @Transactional
    public ProcessTemplateResponse updateTemplate(String templateId, ProcessTemplateUpdateRequest request) {
        permissionService.require(SystemPermission.TEMPLATE_MANAGE);
        ProcessTemplate template = findTemplate(templateId);
        if (hasText(request.templateName())) {
            template.setTemplateName(request.templateName().trim());
        }
        if (hasText(request.templateCategory())) {
            template.setTemplateCategory(request.templateCategory().trim().toLowerCase());
        }
        if (hasText(request.templateType())) {
            template.setTemplateType(normalizeNodeType(request.templateType()));
        }
        if (request.iconKey() != null) {
            template.setIconKey(trimToNull(request.iconKey()));
        }
        if (request.defaultColorScheme() != null) {
            template.setDefaultColorScheme(defaultColorScheme(request.defaultColorScheme(), template.getDefaultColorScheme()));
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
        return toResponse(processTemplateRepository.save(template));
    }

    @Override
    @Transactional
    public void deleteTemplate(String templateId) {
        permissionService.require(SystemPermission.TEMPLATE_MANAGE);
        processTemplateRepository.delete(findTemplate(templateId));
    }

    @Override
    @Transactional
    public ProcessResponse applyTemplate(String templateId, ProcessTemplateApplyRequest request) {
        ProcessTemplate template = findReadableTemplate(templateId);
        Workflow workflow = workflowAccess.requireWorkflowWriteAccess(request.workflowId());

        Process process = new Process();
        process.setProcessId(idGenerator.generate());
        process.setProjectId(workflow.getProjectId());
        process.setWorkflowId(workflow.getWorkflowId());
        process.setTemplateId(template.getTemplateId());
        process.setProcessName(defaultText(request.processName(), template.getTemplateName()));
        process.setProcessType(defaultIfBlank(request.processType(), template.getTemplateCategory()));
        process.setNodeType(normalizeNodeType(defaultIfBlank(request.nodeType(), template.getTemplateType())));
        process.setProcessStatus("active");
        process.setColorScheme(defaultColorScheme(request.colorScheme(), template.getDefaultColorScheme()));
        process.setPosX(defaultIfNull(request.posX(), 0.0));
        process.setPosY(defaultIfNull(request.posY(), 0.0));
        process.setWidth(defaultIfNull(template.getDefaultWidth(), 120.0));
        process.setHeight(defaultIfNull(template.getDefaultHeight(), 60.0));
        process.setProcessDesc(template.getDefaultDesc());
        process.setDeletedYn(NOT_DELETED);
        process.setVersion(1);
        process.setVersionNonce(ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE));
        return toProcessResponse(processRepository.save(process));
    }

    private ProcessTemplate findTemplate(String templateId) {
        return processTemplateRepository.findById(templateId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private ProcessTemplate findReadableTemplate(String templateId) {
        ProcessTemplate template = findTemplate(templateId);
        if (!canReadPrivate() && !PUBLIC_YES.equalsIgnoreCase(template.getPublicYn())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Private process templates require admin access.");
        }
        return template;
    }

    private boolean canReadPrivate() {
        return permissionService.hasPermission(SystemPermission.TEMPLATE_READ_PRIVATE);
    }

    private static void applyTemplateFields(
        ProcessTemplate template,
        String templateName,
        String templateCategory,
        String templateType,
        String iconKey,
        String defaultColorScheme,
        Double defaultWidth,
        Double defaultHeight,
        String defaultDesc,
        String defaultConfig,
        String publicYn,
        Integer sortOrder
    ) {
        template.setTemplateName(templateName.trim());
        template.setTemplateCategory(templateCategory.trim().toLowerCase());
        template.setTemplateType(normalizeNodeType(defaultIfBlank(templateType, "process")));
        template.setIconKey(trimToNull(iconKey));
        template.setDefaultColorScheme(defaultColorScheme(defaultColorScheme, DEFAULT_COLOR_SCHEME));
        template.setDefaultWidth(defaultIfNull(defaultWidth, 120.0));
        template.setDefaultHeight(defaultIfNull(defaultHeight, 60.0));
        template.setDefaultDesc(trimToNull(defaultDesc));
        template.setDefaultConfig(defaultJson(defaultConfig));
        template.setPublicYn(normalizeYn(publicYn));
        template.setSortOrder(sortOrder != null ? sortOrder : 0);
    }

    private static ProcessTemplateResponse toResponse(ProcessTemplate template) {
        return new ProcessTemplateResponse(
            template.getTemplateId(),
            template.getTemplateName(),
            template.getTemplateCategory(),
            template.getTemplateType(),
            template.getIconKey(),
            template.getDefaultColorScheme(),
            template.getDefaultWidth(),
            template.getDefaultHeight(),
            template.getDefaultDesc(),
            template.getDefaultConfig(),
            template.getPublicYn(),
            template.getSortOrder()
        );
    }

    private static ProcessResponse toProcessResponse(Process process) {
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

    private static String defaultColorScheme(String value, String defaultValue) {
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
