package org.myweb.flowmat.domain.catalog.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;
import org.myweb.flowmat.global.common.CreatedUpdatedAuditEntity;

/**
 * A date on which no calendar shift starts, for all of the project's equipment that has a calendar
 * (docs/domain/equipment-schedule.md "휴일").
 */
@Getter
@Setter
@Entity
@Table(name = "project_holiday")
public class ProjectHoliday extends CreatedUpdatedAuditEntity {

    @Id
    private String holidayId;

    private String projectId;
    private LocalDate holidayDate;
    private String holidayName;
}
