package org.myweb.flowmat.domain.rule.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.rule.domain.entity.FlowRule;
import org.myweb.flowmat.domain.rule.repository.FlowRuleRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.domain.rule.application.publicapi.RuleEvaluationContext;
import org.myweb.flowmat.domain.rule.application.publicapi.RuleTarget;

@ExtendWith(MockitoExtension.class)
class FlowRuleEngineServiceImplTest {

    @Mock
    private FlowRuleRepository flowRuleRepository;

    private FlowRuleEngineServiceImpl flowRuleEngineService;

    @BeforeEach
    void setUp() {
        flowRuleEngineService = new FlowRuleEngineServiceImpl(
            flowRuleRepository,
            new FlowRuleExpressionEvaluator(new ObjectMapper())
        );
    }

    @Test
    void blocksWhenRuleConditionMatches() {
        FlowRule rule = new FlowRule();
        rule.setRuleId("rule-1");
        rule.setEnabledYn("Y");
        rule.setConditionType("expression");
        rule.setConditionExpression("inventory.availableQuantity < requestQuantity");
        rule.setActionType("validate");
        rule.setRuleDesc("Insufficient inventory");

        when(flowRuleRepository.findAllByProjectIdAndTargetTypeAndTargetIdAndDeletedYnOrderByPriorityDescCreatedAtAsc(
            eq("project-1"),
            eq("inventory"),
            eq("inventory-1"),
            eq("N")
        )).thenReturn(List.of(rule));

        RuleEvaluationContext context = new RuleEvaluationContext(
            "project-1",
            List.of(new RuleTarget("inventory", "inventory-1")),
            Map.of(
                "inventory", Map.of("availableQuantity", new BigDecimal("2")),
                "requestQuantity", new BigDecimal("5")
            )
        );

        BusinessException exception = assertThrows(BusinessException.class, () -> flowRuleEngineService.validateRules(context));
        assertEquals("Insufficient inventory", exception.getMessage());
    }
}
