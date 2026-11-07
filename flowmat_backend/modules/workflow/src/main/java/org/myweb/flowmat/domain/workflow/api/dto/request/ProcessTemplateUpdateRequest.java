package org.myweb.flowmat.domain.workflow.api.dto.request;

public record ProcessTemplateUpdateRequest(
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
}
