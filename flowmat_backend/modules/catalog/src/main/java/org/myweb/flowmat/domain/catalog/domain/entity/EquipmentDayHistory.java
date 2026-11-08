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
 * One save or removal of an equipment's own dates or downtime with what it held then
 * (docs/architecture/adr/ADR-024-version-save-history.md decisions 7-12, V102). The dates keep only their last save.
 */
@Getter
@Setter
@Entity
@Table(name = "equipment_day_history")
public class EquipmentDayHistory {
    @Id
    private String historyId;
    private String equipmentId;
    /** day (a date's own shifts) or downtime. */
    private String changeKind;
    /** saved or removed. */
    private String action;
    /** A day change's dates; null for a downtime. */
    private LocalDate firstDate;
    private LocalDate lastDate;
    /** A day change's dates then: [{date, shifts: [{shiftStart, shiftEnd}], reason}]; null for a downtime. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "days_json", columnDefinition = "jsonb")
    private String daysJson;
    private String downtimeId;
    private String downtimeType;
    private OffsetDateTime startsAt;
    private OffsetDateTime endsAt;
    private String reason;
    /** The equipment the dates were copied from; null otherwise. */
    private String copiedFrom;
    private String savedBy;
    private OffsetDateTime savedAt;
}
