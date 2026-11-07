package org.myweb.flowmat.domain.bom.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.myweb.flowmat.domain.bom.domain.entity.BomEffectivityChange;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BomEffectivityChangeRepository extends JpaRepository<BomEffectivityChange, String> {
    Optional<BomEffectivityChange> findByBomIdAndRequestId(String bomId, UUID requestId);
    List<BomEffectivityChange> findTop50ByBomIdOrderByPeriodVersionDesc(String bomId);
}
