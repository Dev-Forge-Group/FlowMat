package org.myweb.flowmat.domain.rule.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.rule.api.dto.request.FlowRuleCreateRequest;
import org.myweb.flowmat.domain.rule.api.dto.request.FlowRuleUpdateRequest;
import org.myweb.flowmat.domain.rule.api.dto.response.FlowRuleResponse;
import org.myweb.flowmat.domain.rule.application.FlowRuleService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/flow-rules")
public class FlowRuleController {

    private final FlowRuleService flowRuleService;

    @GetMapping
    public ApiResponse<List<FlowRuleResponse>> listRules(
        @RequestParam(value = "projectId", required = false) String projectId,
        @RequestParam(value = "targetType", required = false) String targetType,
        @RequestParam(value = "targetId", required = false) String targetId
    ) {
        return ApiResponse.ok(flowRuleService.listRules(projectId, targetType, targetId));
    }

    @PostMapping
    public ApiResponse<FlowRuleResponse> createRule(@Valid @RequestBody FlowRuleCreateRequest request) {
        return ApiResponse.ok(flowRuleService.createRule(request));
    }

    @GetMapping("/{ruleId}")
    public ApiResponse<FlowRuleResponse> getRule(@PathVariable("ruleId") String ruleId) {
        return ApiResponse.ok(flowRuleService.getRule(ruleId));
    }

    @PutMapping("/{ruleId}")
    public ApiResponse<FlowRuleResponse> updateRule(
        @PathVariable("ruleId") String ruleId,
        @RequestBody FlowRuleUpdateRequest request
    ) {
        return ApiResponse.ok(flowRuleService.updateRule(ruleId, request));
    }

    @DeleteMapping("/{ruleId}")
    public ApiResponse<Void> deleteRule(@PathVariable("ruleId") String ruleId) {
        flowRuleService.deleteRule(ruleId);
        return ApiResponse.ok(null);
    }
}
