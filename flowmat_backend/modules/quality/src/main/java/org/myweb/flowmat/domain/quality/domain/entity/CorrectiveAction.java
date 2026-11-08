package org.myweb.flowmat.domain.quality.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** One action on a nonconformity: fix this case, remove the cause, or keep it from happening elsewhere. */
@Getter
@Setter
@Entity
@Table(name = "corrective_action")
public class CorrectiveAction {

    @Id
    private String correctiveActionId;

    private String nonconformityId;
    private Integer actionNo;

    /** correction, corrective or preventive. */
    private String actionType;
    private String description;
    private String ownerId;
    private LocalDate dueDate;

    /** open, done or cancelled. */
    private String status;
    private String resultNote;
    private String createdBy;
    private OffsetDateTime createdAt;
    private String finishedBy;
    private OffsetDateTime finishedAt;
}
