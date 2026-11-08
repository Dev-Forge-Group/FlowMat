package org.myweb.flowmat.domain.purchasing.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * @param version       null while the project has no settings
 * @param approvalSteps the members who approve from a value up, lowest value first (PO50)
 */
public record PurchaseSettingResponse(String projectId, BigDecimal approvalLimit, Long version, String updatedBy, OffsetDateTime updatedAt,
                                      List<Step> approvalSteps) {

    /** @param canApprove false once the member can no longer edit the project, so the step waits until it is changed */
    public record Step(BigDecimal fromValue, String approverId, boolean canApprove) {
    }
}
