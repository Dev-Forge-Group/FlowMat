package org.myweb.flowmat.domain.workforce.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerDayOff;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerDayOffRepository extends JpaRepository<WorkerDayOff, String> {

    List<WorkerDayOff> findAllByProjectIdAndUserIdAndLastDayGreaterThanEqualOrderByFirstDayAscDayOffIdAsc(String projectId,
        String userId, LocalDate from);

    /** Days off of the workers that touch [first, last]. */
    List<WorkerDayOff> findAllByProjectIdAndUserIdInAndLastDayGreaterThanEqualAndFirstDayLessThanEqual(String projectId,
        Collection<String> userIds, LocalDate first, LocalDate last);

    Optional<WorkerDayOff> findByDayOffIdAndProjectIdAndUserId(String dayOffId, String projectId, String userId);
}
