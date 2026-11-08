package org.myweb.flowmat.domain.quality.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.quality.domain.entity.InspectionStandard;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionStandardRepository extends JpaRepository<InspectionStandard, String> {

    List<InspectionStandard> findAllByProjectIdAndDeletedYn(String projectId, String deletedYn);

    List<InspectionStandard> findAllByItemIdAndDeletedYn(String itemId, String deletedYn);

    List<InspectionStandard> findAllByItemIdInAndDeletedYn(Collection<String> itemIds, String deletedYn);

    Optional<InspectionStandard> findByStandardIdAndDeletedYn(String standardId, String deletedYn);
}
