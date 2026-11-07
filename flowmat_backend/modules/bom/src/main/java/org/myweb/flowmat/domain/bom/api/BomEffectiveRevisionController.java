package org.myweb.flowmat.domain.bom.api;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.application.publicapi.BomRevisionQuery;
import org.myweb.flowmat.domain.bom.application.publicapi.EffectiveBomView;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/boms/effective")
public class BomEffectiveRevisionController {
    private final BomRevisionQuery query;
    @GetMapping
    public ApiResponse<EffectiveBomView> get(@RequestParam("projectId") String projectId,
        @RequestParam("targetItemId") String targetItemId,
        @RequestParam("on") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate on) {
        return ApiResponse.ok(query.findEffective(projectId,targetItemId,on)
            .orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND,"No approved revision covers the requested date.")));
    }
    @ExceptionHandler({MissingServletRequestParameterException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> invalidQuery(Exception exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error("projectId, targetItemId and on (ISO project calendar date) are required."));
    }
}
