package org.myweb.flowmat.domain.rule.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record FlowRuleCreateRequest(
    @NotBlank String projectId,
    @NotBlank String targetType,
    @NotBlank String targetId,
    @NotBlank String ruleName,
    String ruleDesc,
    String conditionType,
    @NotBlank String conditionExpression,
    String actionType,
    String actionConfig,
    Integer priority,
    String enabledYn
) {
}
