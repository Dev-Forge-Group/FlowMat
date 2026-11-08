package org.myweb.flowmat.domain.workflow.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ProcessTemplateCreateRequest(
    @NotBlank String templateName,
    @NotBlank String templateCategory,
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
