package org.myweb.flowmat.domain.inventory.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.request.StorageLocationCreateRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.StorageLocationUpdateRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.StorageLocationResponse;
import org.myweb.flowmat.domain.inventory.application.StorageLocationService;
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
@RequestMapping("/storage-locations")
public class StorageLocationController {

    private final StorageLocationService storageLocationService;

    @GetMapping
    public ApiResponse<List<StorageLocationResponse>> list(@RequestParam String projectId) {
        return ApiResponse.ok(storageLocationService.list(projectId));
    }

    @PostMapping
    public ApiResponse<StorageLocationResponse> create(@Valid @RequestBody StorageLocationCreateRequest request) {
        return ApiResponse.ok(storageLocationService.create(request));
    }

    @PutMapping("/{locationId}")
    public ApiResponse<StorageLocationResponse> update(
        @PathVariable String locationId,
        @Valid @RequestBody StorageLocationUpdateRequest request
    ) {
        return ApiResponse.ok(storageLocationService.update(locationId, request));
    }

    @DeleteMapping("/{locationId}")
    public ApiResponse<Void> delete(@PathVariable String locationId) {
        storageLocationService.delete(locationId);
        return ApiResponse.ok(null);
    }

    /** Lists the places existing stock records already name; see StorageLocationService#adoptUsedLocations. */
    @PostMapping("/adopt-used")
    public ApiResponse<List<StorageLocationResponse>> adoptUsed(@RequestParam String projectId) {
        return ApiResponse.ok(storageLocationService.adoptUsedLocations(projectId));
    }
}
