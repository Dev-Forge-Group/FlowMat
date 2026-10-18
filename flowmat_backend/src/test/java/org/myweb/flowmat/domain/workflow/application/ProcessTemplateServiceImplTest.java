package org.myweb.flowmat.domain.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessTemplateCreateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessTemplateResponse;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessTemplate;
import org.myweb.flowmat.domain.workflow.repository.ProcessRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessTemplateRepository;
import org.myweb.flowmat.domain.workflow.repository.WorkflowRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.myweb.flowmat.global.rbac.PermissionService;
import org.myweb.flowmat.global.rbac.SystemPermission;

@ExtendWith(MockitoExtension.class)
class ProcessTemplateServiceImplTest {

    @Mock
    private ProcessTemplateRepository processTemplateRepository;

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private ProcessRepository processRepository;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private WorkflowAccessService workflowAccess;

    @Mock
    private PermissionService permissionService;

    @InjectMocks
    private ProcessTemplateServiceImpl processTemplateService;

    @Test
    void nonAdminSeesOnlyPublicTemplates() {
        ProcessTemplate publicTemplate = template("public-1", "Y");
        when(permissionService.hasPermission(SystemPermission.TEMPLATE_READ_PRIVATE)).thenReturn(false);
        when(processTemplateRepository.findAllByPublicYnOrderBySortOrderAscCreatedAtAsc("Y"))
            .thenReturn(List.of(publicTemplate));

        List<ProcessTemplateResponse> response = processTemplateService.listTemplates();

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().templateId()).isEqualTo("public-1");
    }

    @Test
    void adminSeesAllTemplates() {
        ProcessTemplate publicTemplate = template("public-1", "Y");
        ProcessTemplate privateTemplate = template("private-1", "N");
        when(permissionService.hasPermission(SystemPermission.TEMPLATE_READ_PRIVATE)).thenReturn(true);
        when(processTemplateRepository.findAllByOrderBySortOrderAscCreatedAtAsc())
            .thenReturn(List.of(publicTemplate, privateTemplate));

        List<ProcessTemplateResponse> response = processTemplateService.listTemplates();

        assertThat(response).hasSize(2);
    }

    @Test
    void privateTemplateReadRequiresPermission() {
        ProcessTemplate privateTemplate = template("private-1", "N");
        when(permissionService.hasPermission(SystemPermission.TEMPLATE_READ_PRIVATE)).thenReturn(false);
        when(processTemplateRepository.findById("private-1")).thenReturn(Optional.of(privateTemplate));

        assertThatThrownBy(() -> processTemplateService.getTemplate("private-1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void createTemplateRequiresPermission() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
            .when(permissionService).require(SystemPermission.TEMPLATE_MANAGE);

        assertThatThrownBy(() -> processTemplateService.createTemplate(
            new ProcessTemplateCreateRequest("Template", "generic", "process", null, null, null, null, null, null, "Y", 0)
        ))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void adminCanCreateTemplate() {
        when(idGenerator.generate()).thenReturn("template-1");
        when(processTemplateRepository.save(any(ProcessTemplate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProcessTemplateResponse response = processTemplateService.createTemplate(
            new ProcessTemplateCreateRequest("Template", "generic", "process", null, null, null, null, null, null, "Y", 0)
        );

        assertThat(response.templateId()).isEqualTo("template-1");
        assertThat(response.publicYn()).isEqualTo("Y");
    }

    private static ProcessTemplate template(String id, String publicYn) {
        ProcessTemplate template = new ProcessTemplate();
        template.setTemplateId(id);
        template.setTemplateName(id);
        template.setTemplateCategory("generic");
        template.setTemplateType("process");
        template.setPublicYn(publicYn);
        template.setSortOrder(0);
        return template;
    }
}
