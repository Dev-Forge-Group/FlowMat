package org.myweb.flowmat.domain.quality.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * One recorded inspection of a LOT, a run or an item (docs/domain/quality-inspection.md). Never edited: a wrong entry is
 * followed by a new inspection.
 */
@Getter
@Setter
@Entity
@Table(name = "quality_inspection")
public class QualityInspection {

    public static final String PASS = "pass";
    public static final String FAIL = "fail";

    @Id
    private String inspectionId;

    private String projectId;
    private String productionRunId;
    private String processId;
    private String itemId;
    private String lotId;

    /** What was checked, e.g. "Moisture" or "Visual". */
    private String inspectionType;

    /** {@link #PASS} or {@link #FAIL}. The column's 'pending' default is kept for planned inspections later. */
    private String resultStatus;
    private BigDecimal measuredValue;
    private BigDecimal standardMin;
    private BigDecimal standardMax;
    private String unit;
    private String note;
    private String inspectedBy;
    private OffsetDateTime inspectedAt;

    /** The inspection standard followed, if any (docs/domain/inspection-standard.md); it set the check, limits and unit. */
    private String standardId;
}
