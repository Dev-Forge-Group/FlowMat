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
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectWorkflowCommand;
import org.myweb.flowmat.domain.workflow.api.dto.request.WorkflowTemplateCreateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowTemplateResponse;
import org.myweb.flowmat.domain.workflow.domain.entity.WorkflowTemplate;
import org.myweb.flowmat.domain.workflow.repository.WorkflowRepository;
import org.myweb.flowmat.domain.workflow.repository.WorkflowTemplateRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.myweb.flowmat.global.rbac.PermissionService;
import org.myweb.flowmat.global.rbac.SystemPermission;

@ExtendWith(MockitoExtension.class)
class WorkflowTemplateServiceImplTest {

    @Mock
    private WorkflowTemplateRepository workflowTemplateRepository;

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private ProjectWorkflowCommand projectWorkflows;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private ProjectAccessService projectAccessService;

    @Mock
    private PermissionService permissionService;

    @InjectMocks
    private WorkflowTemplateServiceImpl workflowTemplateService;

    @Test
    void nonAdminSeesOnlyPublicTemplates() {
        WorkflowTemplate pub = template("wf-pub", "Y");
        when(permissionService.hasPermission(SystemPermission.TEMPLATE_READ_PRIVATE)).thenReturn(false);
        when(workflowTemplateRepository.findAllByPublicYnOrderBySortOrderAscCreatedAtAsc("Y"))
            .thenReturn(List.of(pub));

        List<WorkflowTemplateResponse> result = workflowTemplateService.listTemplates();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().templateId()).isEqualTo("wf-pub");
    }

    @Test
    void adminSeesAllTemplates() {
        WorkflowTemplate pub = template("wf-pub", "Y");
        WorkflowTemplate priv = template("wf-priv", "N");
        when(permissionService.hasPermission(SystemPermission.TEMPLATE_READ_PRIVATE)).thenReturn(true);
        when(workflowTemplateRepository.findAllByOrderBySortOrderAscCreatedAtAsc())
            .thenReturn(List.of(pub, priv));

        List<WorkflowTemplateResponse> result = workflowTemplateService.listTemplates();

        assertThat(result).hasSize(2);
    }

    @Test
    void privateTemplateReadBlockedWithoutPermission() {
        WorkflowTemplate priv = template("wf-priv", "N");
        when(permissionService.hasPermission(SystemPermission.TEMPLATE_READ_PRIVATE)).thenReturn(false);
        when(workflowTemplateRepository.findById("wf-priv")).thenReturn(Optional.of(priv));

        assertThatThrownBy(() -> workflowTemplateService.getTemplate("wf-priv"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void createTemplateRequiresPermission() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
            .when(permissionService).require(SystemPermission.TEMPLATE_MANAGE);

        assertThatThrownBy(() -> workflowTemplateService.createTemplate(
            new WorkflowTemplateCreateRequest("WF Template", "generic", "main", null, null, null, null, null, "Y", 0)
        ))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void adminCanCreateTemplate() {
        when(idGenerator.generate()).thenReturn("wf-tpl-1");
        when(workflowTemplateRepository.save(any(WorkflowTemplate.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowTemplateResponse response = workflowTemplateService.createTemplate(
            new WorkflowTemplateCreateRequest("WF Template", "generic", "main", null, null, null, null, null, "Y", 0)
        );

        assertThat(response.templateId()).isEqualTo("wf-tpl-1");
        assertThat(response.publicYn()).isEqualTo("Y");
    }

    private static WorkflowTemplate template(String id, String publicYn) {
        WorkflowTemplate t = new WorkflowTemplate();
        t.setTemplateId(id);
        t.setTemplateName(id);
        t.setTemplateCategory("generic");
        t.setTemplateType("main");
        t.setPublicYn(publicYn);
        t.setSortOrder(0);
        return t;
    }
}
