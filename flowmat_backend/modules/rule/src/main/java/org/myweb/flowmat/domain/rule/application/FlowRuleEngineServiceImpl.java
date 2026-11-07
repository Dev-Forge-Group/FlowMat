package org.myweb.flowmat.domain.rule.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.rule.domain.entity.FlowRule;
import org.myweb.flowmat.domain.rule.repository.FlowRuleRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FlowRuleEngineServiceImpl implements FlowRuleEngineService {

    private static final String NOT_DELETED = "N";

    private final FlowRuleRepository flowRuleRepository;
    private final FlowRuleExpressionEvaluator expressionEvaluator;

    @Override
    public void validateRules(RuleEvaluationContext context) {
        List<FlowRule> rules = findApplicableRules(context);
        for (FlowRule rule : rules) {
            if (!"Y".equalsIgnoreCase(rule.getEnabledYn())) {
                continue;
            }
            if (!expressionEvaluator.evaluate(rule.getConditionType(), rule.getConditionExpression(), context.facts())) {
                continue;
            }
            applyAction(rule);
        }
    }

    private List<FlowRule> findApplicableRules(RuleEvaluationContext context) {
        Set<String> seen = new LinkedHashSet<>();
        List<FlowRule> collected = new ArrayList<>();

        for (RuleTarget target : context.targets()) {
            if (target == null || isBlank(target.targetType()) || isBlank(target.targetId())) {
                continue;
            }
            List<FlowRule> rules = flowRuleRepository
                .findAllByProjectIdAndTargetTypeAndTargetIdAndDeletedYnOrderByPriorityDescCreatedAtAsc(
                    context.projectId(),
                    target.targetType().trim().toLowerCase(),
                    target.targetId().trim(),
                    NOT_DELETED
                );
            for (FlowRule rule : rules) {
                if (seen.add(rule.getRuleId())) {
                    collected.add(rule);
                }
            }
        }

        return collected;
    }

    private void applyAction(FlowRule rule) {
        String actionType = rule.getActionType() == null ? "validate" : rule.getActionType().trim().toLowerCase();
        if ("validate".equals(actionType) || "block".equals(actionType) || "reject".equals(actionType)) {
            String message = !isBlank(rule.getRuleDesc()) ? rule.getRuleDesc() : rule.getRuleName();
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
