package org.myweb.flowmat.domain.production.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A numbered step of a work instruction; a required step must be confirmed on the run, some record a value. */
@Getter
@Setter
@Entity
@Table(name = "work_instruction_step")
public class WorkInstructionStep {

    @Id
    private String stepId;

    private String instructionId;
    private Integer stepNo;
    private String stepText;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "required_yn", length = 1)
    private String requiredYn;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "records_value_yn", length = 1)
    private String recordsValueYn;

    /** What the value is, such as "Oven °C"; only for steps that record one. */
    private String valueLabel;

    /** Limits for the recorded value (R7); a value outside them is recorded and marked. */
    private java.math.BigDecimal valueMin;
    private java.math.BigDecimal valueMax;

    /** The inspection standard the limits come from (W13, V71); null when they were typed. */
    private String standardId;

    /** The process the step belongs to (V91, docs/architecture/adr/ADR-011-production-steps.md decision 6); null for the whole run. */
    private String processId;
}
