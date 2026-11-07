package org.myweb.flowmat.domain.rule.api.dto.response;

public record FlowRuleResponse(
    String ruleId,
    String projectId,
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
