package org.myweb.flowmat.domain.rule.application.publicapi;

public interface FlowRuleEngineService {

    void validateRules(RuleEvaluationContext context);
}
