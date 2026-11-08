package org.myweb.flowmat.domain.quality.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.quality.domain.entity.NonconformityDefect;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NonconformityDefectRepository extends JpaRepository<NonconformityDefect, String> {

    List<NonconformityDefect> findAllByNonconformityIdIn(Collection<String> nonconformityIds);

    List<NonconformityDefect> findAllByNonconformityId(String nonconformityId);

    Optional<NonconformityDefect> findByDefectLogId(String defectLogId);
}
