package org.myweb.flowmat.domain.quality.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A defect found in a LOT, a run or an item (docs/domain/quality-inspection.md). Logged once, resolved once; logging a
 * defect moves no stock.
 */
@Getter
@Setter
@Entity
@Table(name = "defect_log")
public class DefectLog {

    @Id
    private String defectLogId;

    private String projectId;
    private String productionRunId;
    private String processId;
    private String itemId;
    private String lotId;

    /** The inspection that found it, when there was one. */
    private String inspectionId;
    private String defectCode;
    private String defectType;
    private String defectLocation;
    private BigDecimal quantity;

    /** minor, major or critical. */
    private String severity;
    private String reason;
    private String actionTaken;
    private String imageUrl;

    @JdbcTypeCode(SqlTypes.CHAR)
    private String resolvedYn;
    private OffsetDateTime resolvedAt;
    private OffsetDateTime detectedAt;
    private String loggedBy;
    private String resolvedBy;
    private OffsetDateTime loggedAt;

    public boolean isResolved() {
        return "Y".equals(resolvedYn);
    }
}
