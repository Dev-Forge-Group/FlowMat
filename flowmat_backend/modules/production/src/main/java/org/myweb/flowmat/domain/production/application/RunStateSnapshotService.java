package org.myweb.flowmat.domain.production.application;

import java.util.List;
import org.myweb.flowmat.domain.production.api.dto.request.RunStateSnapshotCreateRequest;
import org.myweb.flowmat.domain.production.api.dto.response.RunStateSnapshotResponse;

public interface RunStateSnapshotService {

    List<RunStateSnapshotResponse> listSnapshots(String productionRunId);

    RunStateSnapshotResponse createSnapshot(RunStateSnapshotCreateRequest request);

    RunStateSnapshotResponse getSnapshot(String runStateSnapshotId);
}
