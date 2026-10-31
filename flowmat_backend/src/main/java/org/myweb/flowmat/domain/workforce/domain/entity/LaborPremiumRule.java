package org.myweb.flowmat.domain.workforce.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The project's pay premiums from a project date (docs/architecture/adr/ADR-028-pay-premiums.md, V126). Every save is a new row;
 * a rule removed before it started stays marked. A premium left empty is not paid; a rule with none stops them from its date.
 */
@Getter
@Setter
@Entity
@Table(name = "labor_premium_rule")
public class LaborPremiumRule {
    @Id
    private String ruleId;
    private String projectId;
    /** The worker whose own premiums these are (V130); null for the project's. */
    private String userId;
    private LocalDate effectiveFrom;
    /** Minutes of a work day after which the overtime percent is added, with it or not at all. */
    private Integer overtimeAfterMinutes;
    private Integer overtimePercent;
    /** In project time; the window runs past midnight when it ends earlier than it starts. */
    private LocalTime nightStart;
    private LocalTime nightEnd;
    private Integer nightPercent;
    /** Added to every minute of a work day that is a project holiday. */
    private Integer holidayPercent;
    /** Regular minutes of a week after which the weekly percent is added (V127), with it and the week's ISO start day, or none. */
    private Integer weeklyOvertimeAfterMinutes;
    private Integer weeklyOvertimePercent;
    private Integer weekStartsOn;
    /** Y: a work day without any of the worker's scheduled time gets the holiday percent too (V128). */
    @JdbcTypeCode(SqlTypes.CHAR)
    private String restDaysAsHolidaysYn;
    /** Minutes of a holiday after which the holiday overtime percent is added to the holiday one, in place of the daily (V129). */
    private Integer holidayOvertimeAfterMinutes;
    private Integer holidayOvertimePercent;
    /** Y: from its date the worker follows the project's premiums again; such a save has none (V130). */
    @JdbcTypeCode(SqlTypes.CHAR)
    private String followProjectYn;
    private String reason;
    private String setBy;
    private OffsetDateTime setAt;
    @JdbcTypeCode(SqlTypes.CHAR)
    private String removedYn;
    private String removedBy;
    private OffsetDateTime removedAt;

    public boolean isRemoved() {
        return "Y".equals(removedYn);
    }

    public boolean isRestDaysAsHolidays() {
        return "Y".equals(restDaysAsHolidaysYn);
    }

    public boolean isFollowProject() {
        return "Y".equals(followProjectYn);
    }
}
