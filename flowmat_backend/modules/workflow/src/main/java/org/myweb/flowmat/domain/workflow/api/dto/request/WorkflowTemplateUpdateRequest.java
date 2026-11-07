package org.myweb.flowmat.domain.workflow.api.dto.request;

public record WorkflowTemplateUpdateRequest(
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
}
