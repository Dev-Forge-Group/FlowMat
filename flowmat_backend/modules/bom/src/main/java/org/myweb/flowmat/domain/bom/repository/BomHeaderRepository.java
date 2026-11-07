package org.myweb.flowmat.domain.bom.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.bom.domain.entity.BomHeader;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BomHeaderRepository extends JpaRepository<BomHeader, String> {

    Optional<BomHeader> findByBomIdAndDeletedYn(String bomId, String deletedYn);

    List<BomHeader> findAllByProjectIdAndDeletedYnOrderByTargetItemIdAscBomVersionDesc(String projectId, String deletedYn);

    List<BomHeader> findAllByProjectIdAndTargetItemIdAndDeletedYnOrderByBomVersionDesc(
        String projectId, String targetItemId, String deletedYn);

    Optional<BomHeader> findTopByProjectIdAndTargetItemIdAndDeletedYnOrderByBomVersionDesc(
        String projectId, String targetItemId, String deletedYn);

    Optional<BomHeader> findTopByProjectIdAndTargetItemIdOrderByBomVersionDesc(String projectId, String targetItemId);

    List<BomHeader> findAllByProjectIdAndBomStatusAndDeletedYn(String projectId, String bomStatus, String deletedYn);
}
