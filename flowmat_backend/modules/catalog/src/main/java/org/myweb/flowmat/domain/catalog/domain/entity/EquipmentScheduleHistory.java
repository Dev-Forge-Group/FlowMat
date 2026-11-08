package org.myweb.flowmat.domain.catalog.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One save or removal of an equipment calendar version with the shifts it held then
 * (docs/architecture/adr/ADR-024-version-save-history.md, V101). No shifts means around the clock from then.
 */
@Getter
@Setter
@Entity
@Table(name = "equipment_schedule_history")
public class EquipmentScheduleHistory {
    @Id
    private String historyId;
    private String equipmentId;
    private String scheduleId;
    private LocalDate effectiveFrom;
    /** saved or removed. */
    private String action;
    private String reason;
    /** The shifts then: [{shiftStart, shiftEnd, workDays}]. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shifts_json", columnDefinition = "jsonb")
    private String shiftsJson;
    private String savedBy;
    private OffsetDateTime savedAt;
}
