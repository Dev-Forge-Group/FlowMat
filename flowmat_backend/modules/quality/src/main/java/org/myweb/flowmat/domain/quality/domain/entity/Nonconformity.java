package org.myweb.flowmat.domain.quality.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** A nonconformity report (NCR): what went wrong, why, what happens to the product (docs/domain/nonconformity.md). */
@Getter
@Setter
@Entity
@Table(name = "nonconformity")
public class Nonconformity {

    @Id
    private String nonconformityId;

    private String projectId;

    /** NCR-0001, NCR-0002 ... per project. */
    private String ncrNo;
    private String title;
    private String description;

    /** minor, major or critical. */
    private String severity;

    /** open, closed or cancelled. */
    private String status;
    private String itemId;
    private String lotId;
    private String productionRunId;
    private String rootCause;

    /** pending, use_as_is, rework, scrap or return_to_supplier. */
    private String disposition;
    private String raisedBy;
    private OffsetDateTime raisedAt;
    private String closedBy;
    private OffsetDateTime closedAt;
    private String closureNote;

    /** effective or not_effective once the closed nonconformity's actions were checked (N12, V48); null before. */
    private String verificationResult;
    private String verificationNote;
    private String verifiedBy;
    private OffsetDateTime verifiedAt;

    /** The day a closed nonconformity's actions are due to be checked (N13, V70); null when none was set. */
    private LocalDate verificationDueDate;
}
