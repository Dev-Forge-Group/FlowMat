package org.myweb.flowmat.domain.production.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** An operator's confirmation of one instruction step on one production run (docs/domain/work-instruction.md). */
@Getter
@Setter
@Entity
@Table(name = "run_instruction_check")
public class RunInstructionCheck {

    @Id
    private String checkId;

    private String productionRunId;
    private String instructionId;
    private String stepId;
    private String checkValue;
    private String note;
    private String checkedBy;
    private OffsetDateTime checkedAt;
    /** Set when the confirmation was undone; the row stays as history (docs/domain/work-instruction.md R6). */
    private String undoneBy;
    private OffsetDateTime undoneAt;
    /** The quality inspection this confirmation also recorded (R9, V72): a step that follows a standard with a limit. */
    private String inspectionId;
    /** pass or fail; the inspection does not change, so its result is kept here. */
    private String inspectionResult;
}
