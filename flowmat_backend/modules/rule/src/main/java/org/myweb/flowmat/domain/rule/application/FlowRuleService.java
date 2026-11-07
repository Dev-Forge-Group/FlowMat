package org.myweb.flowmat.domain.rule.application;

import java.util.List;
import org.myweb.flowmat.domain.rule.api.dto.request.FlowRuleCreateRequest;
import org.myweb.flowmat.domain.rule.api.dto.request.FlowRuleUpdateRequest;
import org.myweb.flowmat.domain.rule.api.dto.response.FlowRuleResponse;

public interface FlowRuleService {

    List<FlowRuleResponse> listRules(String projectId, String targetType, String targetId);

    FlowRuleResponse createRule(FlowRuleCreateRequest request);

    FlowRuleResponse getRule(String ruleId);

    FlowRuleResponse updateRule(String ruleId, FlowRuleUpdateRequest request);

    void deleteRule(String ruleId);
}
