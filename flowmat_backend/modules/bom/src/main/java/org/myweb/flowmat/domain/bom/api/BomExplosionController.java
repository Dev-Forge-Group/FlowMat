package org.myweb.flowmat.domain.bom.api;

import lombok.RequiredArgsConstructor;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.myweb.flowmat.domain.bom.api.dto.response.BomExplosionResponse;
import org.myweb.flowmat.domain.bom.application.BomExplosionService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Multi-level BOM explosion (docs/domain/multi-level-bom.md). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/boms")
public class BomExplosionController {

    private final BomExplosionService bomExplosionService;

    /** Only an approved revision can be exploded; {@code quantity} is in the BOM item's own unit. */
    @GetMapping("/{bomId}/explosion")
    public ApiResponse<BomExplosionResponse> explode(
        @PathVariable("bomId") String bomId,
        @RequestParam(value = "quantity", required = false) String quantity,
        @RequestParam(value = "on", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on
    ) {
        return ApiResponse.ok(bomExplosionService.explode(bomId, quantity, on));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> invalidDay(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error("on must be a calendar date such as 2030-02-01."));
    }
}
