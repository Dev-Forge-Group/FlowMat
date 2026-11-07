package org.myweb.flowmat.domain.production.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.RunStateSnapshotCreateRequest;
import org.myweb.flowmat.domain.production.api.dto.response.RunStateSnapshotResponse;
import org.myweb.flowmat.domain.production.application.RunStateSnapshotService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/run-state-snapshots")
public class RunStateSnapshotController {

    private final RunStateSnapshotService runStateSnapshotService;

    @GetMapping
    public ApiResponse<List<RunStateSnapshotResponse>> listSnapshots(
        @RequestParam("productionRunId") String productionRunId
    ) {
        return ApiResponse.ok(runStateSnapshotService.listSnapshots(productionRunId));
    }

    @PostMapping
    public ApiResponse<RunStateSnapshotResponse> createSnapshot(
        @Valid @RequestBody RunStateSnapshotCreateRequest request
    ) {
        return ApiResponse.ok(runStateSnapshotService.createSnapshot(request));
    }

    @GetMapping("/{runStateSnapshotId}")
    public ApiResponse<RunStateSnapshotResponse> getSnapshot(
        @PathVariable("runStateSnapshotId") String runStateSnapshotId
    ) {
        return ApiResponse.ok(runStateSnapshotService.getSnapshot(runStateSnapshotId));
    }
}
