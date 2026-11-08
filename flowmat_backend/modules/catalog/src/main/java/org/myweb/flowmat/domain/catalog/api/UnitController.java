package org.myweb.flowmat.domain.catalog.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.request.UnitCreateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.request.UnitUpdateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.response.UnitResponse;
import org.myweb.flowmat.domain.catalog.application.UnitService;
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
@RequestMapping("/units")
public class UnitController {

    private final UnitService unitService;

    @GetMapping
    public ApiResponse<List<UnitResponse>> listUnits(
        @RequestParam(value = "includeInactive", defaultValue = "false") boolean includeInactive
    ) {
        return ApiResponse.ok(unitService.listUnits(includeInactive));
    }

    @PostMapping
    public ApiResponse<UnitResponse> createUnit(@Valid @RequestBody UnitCreateRequest request) {
        return ApiResponse.ok(unitService.createUnit(request));
    }

    @PutMapping("/{unitId}")
    public ApiResponse<UnitResponse> updateUnit(
        @PathVariable("unitId") String unitId,
        @RequestBody UnitUpdateRequest request
    ) {
        return ApiResponse.ok(unitService.updateUnit(unitId, request));
    }

    /** Deactivates rather than deletes; see UnitServiceImpl#deactivateUnit. */
    @DeleteMapping("/{unitId}")
    public ApiResponse<Void> deactivateUnit(@PathVariable("unitId") String unitId) {
        unitService.deactivateUnit(unitId);
        return ApiResponse.ok(null);
    }
}
