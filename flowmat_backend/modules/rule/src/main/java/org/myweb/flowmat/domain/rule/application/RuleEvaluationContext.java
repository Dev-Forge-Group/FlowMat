package org.myweb.flowmat.domain.rule.application;

import java.util.List;
import java.util.Map;

public record RuleEvaluationContext(
    String projectId,
    List<RuleTarget> targets,
    Map<String, Object> facts
) {
}
