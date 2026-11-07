package org.myweb.flowmat.domain.bom.api;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.api.dto.request.BomApproveRequest;
import org.myweb.flowmat.domain.bom.api.dto.request.BomCopyRequest;
import org.myweb.flowmat.domain.bom.api.dto.request.BomCreateRequest;
import org.myweb.flowmat.domain.bom.api.dto.request.BomLineCreateRequest;
import org.myweb.flowmat.domain.bom.api.dto.request.BomLineImportRequest;
import org.myweb.flowmat.domain.bom.api.dto.request.BomUpdateRequest;
import org.myweb.flowmat.domain.bom.api.dto.request.BomRevisionRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.myweb.flowmat.domain.bom.api.dto.response.BomLineImportResponse;
import org.myweb.flowmat.domain.bom.api.dto.response.BomRequirementResponse;
import org.myweb.flowmat.domain.bom.api.dto.response.BomResponse;
import org.myweb.flowmat.domain.bom.api.dto.response.BomWhereUsedResponse;
import org.myweb.flowmat.domain.bom.application.BomApprovalService;
import org.myweb.flowmat.domain.bom.application.BomCopyService;
import org.myweb.flowmat.domain.bom.application.BomLineImportService;
import org.myweb.flowmat.domain.bom.application.BomService;
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

/** BOM revisions (docs/domain/inventory-bom-lot-contract.md §5). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/boms")
public class BomController {

    private final BomService bomService;
    private final BomApprovalService bomApprovalService;
    private final BomCopyService bomCopyService;
    private final BomLineImportService bomLineImportService;

    @GetMapping
    public ApiResponse<List<BomResponse>> listBoms(
        @RequestParam("projectId") String projectId,
        @RequestParam(value = "targetItemId", required = false) String targetItemId
    ) {
        return ApiResponse.ok(bomService.listBoms(projectId, targetItemId));
    }

    /** The BOM revisions that use an item as a line. */
    @GetMapping("/where-used")
    public ApiResponse<List<BomWhereUsedResponse>> whereUsed(
        @RequestParam("projectId") String projectId,
        @RequestParam("itemId") String itemId
    ) {
        return ApiResponse.ok(bomService.whereUsed(projectId, itemId));
    }

    @PostMapping
    public ApiResponse<BomResponse> createBom(@Valid @RequestBody BomCreateRequest request) {
        return ApiResponse.ok(bomService.createBom(request));
    }

    @GetMapping("/{bomId}")
    public ApiResponse<BomResponse> getBom(@PathVariable("bomId") String bomId) {
        return ApiResponse.ok(bomService.getBom(bomId));
    }

    @PutMapping("/{bomId}")
    public ApiResponse<BomResponse> updateBom(@PathVariable("bomId") String bomId, @RequestBody BomUpdateRequest request) {
        return ApiResponse.ok(bomService.updateBom(bomId, request));
    }

    @DeleteMapping("/{bomId}")
    public ApiResponse<Void> deleteBom(@PathVariable("bomId") String bomId) {
        bomService.deleteBom(bomId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{bomId}/lines")
    public ApiResponse<BomResponse> addLine(
        @PathVariable("bomId") String bomId,
        @Valid @RequestBody BomLineCreateRequest request
    ) {
        return ApiResponse.ok(bomService.addLine(bomId, request));
    }

    /** Materials for a draft from a spreadsheet (docs/domain/item-import.md "BOM 자재"); all rows or none. */
    @PostMapping("/{bomId}/lines/import")
    public ApiResponse<BomLineImportResponse> importLines(
        @PathVariable("bomId") String bomId,
        @RequestBody BomLineImportRequest request
    ) {
        return ApiResponse.ok(bomLineImportService.importLines(bomId, request));
    }

    /** Another product's first draft, from this BOM's base and materials. */
    @PostMapping("/{bomId}/copy")
    public ApiResponse<BomResponse> copy(@PathVariable("bomId") String bomId, @Valid @RequestBody BomCopyRequest request) {
        return ApiResponse.ok(bomCopyService.copy(bomId, request));
    }

    @DeleteMapping("/{bomId}/lines/{bomLineId}")
    public ApiResponse<BomResponse> deleteLine(
        @PathVariable("bomId") String bomId,
        @PathVariable("bomLineId") String bomLineId
    ) {
        return ApiResponse.ok(bomService.deleteLine(bomId, bomLineId));
    }

    @PostMapping("/{bomId}/submit")
    public ApiResponse<BomResponse> submit(@PathVariable("bomId") String bomId) {
        return ApiResponse.ok(bomApprovalService.submit(bomId));
    }

    @PostMapping("/{bomId}/approve")
    public ApiResponse<BomResponse> approve(
        @PathVariable("bomId") String bomId,
        @RequestBody(required = false) BomApproveRequest request
    ) {
        return ApiResponse.ok(bomApprovalService.approve(bomId, note(request),
            request != null && Boolean.TRUE.equals(request.endEarlier())));
    }

    @PostMapping("/{bomId}/reject")
    public ApiResponse<BomResponse> reject(
        @PathVariable("bomId") String bomId,
        @RequestBody(required = false) BomApproveRequest request
    ) {
        return ApiResponse.ok(bomApprovalService.reject(bomId, note(request)));
    }

    @PostMapping("/{bomId}/retire")
    public ApiResponse<BomResponse> retire(
        @PathVariable("bomId") String bomId,
        @RequestBody(required = false) BomApproveRequest request
    ) {
        return ApiResponse.ok(bomApprovalService.retire(bomId, note(request)));
    }

    @PostMapping("/{bomId}/revisions")
    public ApiResponse<BomResponse> createRevision(@PathVariable("bomId") String bomId,
        @RequestBody(required = false) BomRevisionRequest request) {
        return ApiResponse.ok(bomService.createRevision(bomId, request == null ? null : request.requestId()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error("Invalid BOM request fields: requestId must be a UUID and quantities must be numbers."));
    }

    @GetMapping("/{bomId}/requirements")
    public ApiResponse<BomRequirementResponse> requirements(
        @PathVariable("bomId") String bomId,
        @RequestParam("quantity") BigDecimal quantity
    ) {
        return ApiResponse.ok(bomService.calculateRequirements(bomId, quantity));
    }

    private static String note(BomApproveRequest request) {
        return request == null ? null : request.note();
    }
}
