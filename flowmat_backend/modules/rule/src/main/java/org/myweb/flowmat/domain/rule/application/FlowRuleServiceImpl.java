package org.myweb.flowmat.domain.rule.application;

import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.rule.api.dto.request.FlowRuleCreateRequest;
import org.myweb.flowmat.domain.rule.api.dto.request.FlowRuleUpdateRequest;
import org.myweb.flowmat.domain.rule.api.dto.response.FlowRuleResponse;
import org.myweb.flowmat.domain.rule.domain.entity.FlowRule;
import org.myweb.flowmat.domain.rule.repository.FlowRuleRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FlowRuleServiceImpl implements FlowRuleService {

    private static final String NOT_DELETED = "N";
    private static final String DELETED = "Y";
    private static final Set<String> ALLOWED_TARGET_TYPES = Set.of(
        "project",
        "workflow",
        "process",
        "process_io",
        "process_connection",
        "item",
        "inventory",
        "run"
    );

    private final FlowRuleRepository flowRuleRepository;
    private final IdGenerator idGenerator;
    private final ProjectAccessService projectAccessService;
    private final FlowRuleExpressionEvaluator expressionEvaluator;

    @Override
    public List<FlowRuleResponse> listRules(String projectId, String targetType, String targetId) {
        if (!hasText(projectId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId is required.");
        }
        projectAccessService.requireProjectReadAccess(projectId.trim());
        if (hasText(targetType) && !hasText(targetId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        if (!hasText(targetType) && hasText(targetId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }

        List<FlowRule> rules;
        if (hasText(targetType) && hasText(targetId)) {
            rules = flowRuleRepository.findAllByProjectIdAndTargetTypeAndTargetIdAndDeletedYnOrderByPriorityDescCreatedAtAsc(
                projectId.trim(),
                normalizeTargetType(targetType),
                targetId.trim(),
                NOT_DELETED
            );
        } else {
            rules = flowRuleRepository.findAllByProjectIdAndDeletedYnOrderByPriorityDescCreatedAtAsc(projectId.trim(), NOT_DELETED);
        }

        return rules.stream()
            .map(FlowRuleServiceImpl::toResponse)
            .toList();
    }

    @Override
    @Transactional
    public FlowRuleResponse createRule(FlowRuleCreateRequest request) {
        projectAccessService.requireProjectWriteAccess(request.projectId().trim());

        FlowRule rule = new FlowRule();
        rule.setRuleId(idGenerator.generate());
        rule.setProjectId(request.projectId().trim());
        rule.setTargetType(normalizeTargetType(request.targetType()));
        rule.setTargetId(request.targetId().trim());
        rule.setRuleName(request.ruleName().trim());
        rule.setRuleDesc(trimToNull(request.ruleDesc()));
        rule.setConditionType(defaultIfBlank(request.conditionType(), "expression"));
        rule.setConditionExpression(request.conditionExpression().trim());
        rule.setActionType(defaultIfBlank(request.actionType(), "validate"));
        rule.setActionConfig(defaultJson(request.actionConfig()));
        rule.setPriority(request.priority() != null ? request.priority() : 0);
        rule.setEnabledYn(normalizeYn(request.enabledYn()));
        rule.setDeletedYn(NOT_DELETED);
        validateCondition(rule);
        return toResponse(flowRuleRepository.save(rule));
    }

    @Override
    public FlowRuleResponse getRule(String ruleId) {
        FlowRule rule = findRule(ruleId);
        projectAccessService.requireProjectReadAccess(rule.getProjectId());
        return toResponse(rule);
    }

    @Override
    @Transactional
    public FlowRuleResponse updateRule(String ruleId, FlowRuleUpdateRequest request) {
        FlowRule rule = findRule(ruleId);
        projectAccessService.requireProjectWriteAccess(rule.getProjectId());

        if (hasText(request.targetType())) {
            rule.setTargetType(normalizeTargetType(request.targetType()));
        }
        if (hasText(request.targetId())) {
            rule.setTargetId(request.targetId().trim());
        }
        if (hasText(request.ruleName())) {
            rule.setRuleName(request.ruleName().trim());
        }
        if (request.ruleDesc() != null) {
            rule.setRuleDesc(trimToNull(request.ruleDesc()));
        }
        if (hasText(request.conditionType())) {
            rule.setConditionType(request.conditionType().trim().toLowerCase());
        }
        if (hasText(request.conditionExpression())) {
            rule.setConditionExpression(request.conditionExpression().trim());
        }
        if (hasText(request.actionType())) {
            rule.setActionType(request.actionType().trim().toLowerCase());
        }
        if (request.actionConfig() != null) {
            rule.setActionConfig(defaultJson(request.actionConfig()));
        }
        if (request.priority() != null) {
            rule.setPriority(request.priority());
        }
        if (request.enabledYn() != null) {
            rule.setEnabledYn(normalizeYn(request.enabledYn()));
        }
        validateCondition(rule);
        return toResponse(flowRuleRepository.save(rule));
    }

    private void validateCondition(FlowRule rule) {
        if ("expression".equals(rule.getConditionType())) {
            expressionEvaluator.validate(rule.getConditionExpression());
        }
    }

    @Override
    @Transactional
    public void deleteRule(String ruleId) {
        FlowRule rule = findRule(ruleId);
        projectAccessService.requireProjectOwnerAccess(rule.getProjectId());
        rule.setDeletedYn(DELETED);
        flowRuleRepository.save(rule);
    }

    private FlowRule findRule(String ruleId) {
        return flowRuleRepository.findByRuleIdAndDeletedYn(ruleId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static FlowRuleResponse toResponse(FlowRule rule) {
        return new FlowRuleResponse(
            rule.getRuleId(),
            rule.getProjectId(),
            rule.getTargetType(),
            rule.getTargetId(),
            rule.getRuleName(),
            rule.getRuleDesc(),
            rule.getConditionType(),
            rule.getConditionExpression(),
            rule.getActionType(),
            rule.getActionConfig(),
            rule.getPriority(),
            rule.getEnabledYn()
        );
    }

    private static String normalizeTargetType(String value) {
        String normalized = value.trim().toLowerCase();
        if (!ALLOWED_TARGET_TYPES.contains(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        return normalized;
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return hasText(value) ? value.trim().toLowerCase() : defaultValue;
    }

    private static String defaultJson(String value) {
        return hasText(value) ? value.trim() : "{}";
    }

    private static String normalizeYn(String value) {
        return "N".equalsIgnoreCase(value) ? "N" : "Y";
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
