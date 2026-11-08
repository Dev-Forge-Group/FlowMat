package org.myweb.flowmat.domain.workforce.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workforce.api.dto.request.LaborPremiumRuleRequest;
import org.myweb.flowmat.domain.workforce.api.dto.response.LaborPremiumRuleResponse;
import org.myweb.flowmat.domain.workforce.api.dto.response.WorkerAttendancePayResponse;
import org.myweb.flowmat.domain.workforce.application.LaborPremiumService;
import org.myweb.flowmat.domain.workforce.application.WorkerAttendancePayService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The project's pay premiums and a worker's attendance as pay, for the project owner only
 * (docs/architecture/adr/ADR-028-pay-premiums.md).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/projects/{projectId}")
public class LaborPremiumController {

    private final LaborPremiumService premiums;
    private final WorkerAttendancePayService pay;

    @GetMapping("/labor-premiums")
    public ApiResponse<LaborPremiumRuleResponse> rules(@PathVariable("projectId") String projectId) {
        return ApiResponse.ok(premiums.rules(projectId));
    }

    @PutMapping("/labor-premiums")
    public ApiResponse<LaborPremiumRuleResponse> setRule(@PathVariable("projectId") String projectId,
                                                         @RequestBody(required = false) LaborPremiumRuleRequest request) {
        return ApiResponse.ok(premiums.setRule(projectId, request));
    }

    @DeleteMapping("/labor-premiums/{ruleId}")
    public ApiResponse<LaborPremiumRuleResponse> removeRule(@PathVariable("projectId") String projectId,
                                                            @PathVariable("ruleId") String ruleId) {
        return ApiResponse.ok(premiums.removeRule(projectId, ruleId));
    }

    @GetMapping("/workers/{userId}/labor-premiums")
    public ApiResponse<LaborPremiumRuleResponse> workerRules(@PathVariable("projectId") String projectId,
                                                             @PathVariable("userId") String userId) {
        return ApiResponse.ok(premiums.workerRules(projectId, userId));
    }

    @PutMapping("/workers/{userId}/labor-premiums")
    public ApiResponse<LaborPremiumRuleResponse> setWorkerRule(@PathVariable("projectId") String projectId,
                                                               @PathVariable("userId") String userId,
                                                               @RequestBody(required = false) LaborPremiumRuleRequest request) {
        return ApiResponse.ok(premiums.setWorkerRule(projectId, userId, request));
    }

    @DeleteMapping("/workers/{userId}/labor-premiums/{ruleId}")
    public ApiResponse<LaborPremiumRuleResponse> removeWorkerRule(@PathVariable("projectId") String projectId,
                                                                  @PathVariable("userId") String userId,
                                                                  @PathVariable("ruleId") String ruleId) {
        return ApiResponse.ok(premiums.removeWorkerRule(projectId, userId, ruleId));
    }

    @GetMapping("/workers/{userId}/attendance-pay")
    public ApiResponse<WorkerAttendancePayResponse> pay(@PathVariable("projectId") String projectId,
                                                        @PathVariable("userId") String userId,
                                                        @RequestParam(value = "from", required = false) String from,
                                                        @RequestParam(value = "to", required = false) String to) {
        return ApiResponse.ok(pay.pay(projectId, userId, from, to));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error("Send the premiums as JSON, with minutes and percents as whole numbers."));
    }
}
