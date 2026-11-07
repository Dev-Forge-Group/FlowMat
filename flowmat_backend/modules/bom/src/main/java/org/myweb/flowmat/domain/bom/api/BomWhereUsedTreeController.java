package org.myweb.flowmat.domain.bom.api;

import lombok.RequiredArgsConstructor;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.myweb.flowmat.domain.bom.api.dto.response.BomWhereUsedTreeResponse;
import org.myweb.flowmat.domain.bom.application.BomWhereUsedTreeService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Where an item is used at every level, through approved BOMs (docs/domain/multi-level-bom.md). Read only. */
@RestController
@RequiredArgsConstructor
public class BomWhereUsedTreeController {

    private final BomWhereUsedTreeService bomWhereUsedTreeService;

    @GetMapping("/boms/where-used/all-levels")
    public ApiResponse<BomWhereUsedTreeResponse> allLevels(
        @RequestParam("projectId") String projectId,
        @RequestParam("itemId") String itemId,
        @RequestParam(value = "on", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on
    ) {
        return ApiResponse.ok(bomWhereUsedTreeService.allLevels(projectId, itemId, on));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> invalidDay(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error("on must be a calendar date such as 2030-02-01."));
    }
}
