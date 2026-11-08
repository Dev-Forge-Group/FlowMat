package org.myweb.flowmat.domain.production.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.production.domain.entity.RunStateSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunStateSnapshotRepository extends JpaRepository<RunStateSnapshot, String> {

    List<RunStateSnapshot> findAllByProductionRunIdOrderByCreatedAtDesc(String productionRunId);

    Optional<RunStateSnapshot> findByRunStateSnapshotId(String runStateSnapshotId);
}
