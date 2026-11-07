package org.myweb.flowmat.domain.bom.api;

import lombok.RequiredArgsConstructor;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.myweb.flowmat.domain.bom.api.dto.response.BomCostRollupResponse;
import org.myweb.flowmat.domain.bom.application.BomCostRollupService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Material cost per unit of every made item, rolled up through approved BOMs (docs/domain/multi-level-bom.md). Read only. */
@RestController
@RequiredArgsConstructor
public class BomCostRollupController {

    private final BomCostRollupService bomCostRollupService;

    @GetMapping("/boms/cost-rollup")
    public ApiResponse<BomCostRollupResponse> rollup(@RequestParam("projectId") String projectId,
        @RequestParam(value = "on", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {
        return ApiResponse.ok(bomCostRollupService.rollup(projectId, on));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> invalidDay(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error("on must be a calendar date such as 2030-02-01."));
    }
}
