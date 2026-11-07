package org.myweb.flowmat.domain.quality.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.quality.domain.entity.CorrectiveAction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CorrectiveActionRepository extends JpaRepository<CorrectiveAction, String> {

    List<CorrectiveAction> findAllByNonconformityIdInOrderByActionNoAsc(Collection<String> nonconformityIds);

    List<CorrectiveAction> findAllByNonconformityIdOrderByActionNoAsc(String nonconformityId);

    Optional<CorrectiveAction> findByCorrectiveActionIdAndNonconformityId(String correctiveActionId, String nonconformityId);
}
