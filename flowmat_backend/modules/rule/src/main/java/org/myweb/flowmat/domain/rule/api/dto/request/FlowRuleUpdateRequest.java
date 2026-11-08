package org.myweb.flowmat.domain.rule.api.dto.request;

public record FlowRuleUpdateRequest(
    String targetType,
    String targetId,
    String ruleName,
    String ruleDesc,
    String conditionType,
    String conditionExpression,
    String actionType,
    String actionConfig,
    Integer priority,
    String enabledYn
) {
}
