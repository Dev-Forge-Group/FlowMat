package org.myweb.flowmat.domain.catalog.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.catalog.domain.entity.ProjectHoliday;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectHolidayRepository extends JpaRepository<ProjectHoliday, String> {

    List<ProjectHoliday> findAllByProjectIdAndDeletedYnOrderByHolidayDateAsc(String projectId, String deletedYn);

    /** Holidays from one date to another, both included (shifts of a window, which starts the day before it). */
    List<ProjectHoliday> findAllByProjectIdAndHolidayDateBetweenAndDeletedYn(String projectId, LocalDate from, LocalDate to, String deletedYn);

    boolean existsByProjectIdAndHolidayDateAndDeletedYn(String projectId, LocalDate holidayDate, String deletedYn);

    Optional<ProjectHoliday> findByHolidayIdAndDeletedYn(String holidayId, String deletedYn);
}
