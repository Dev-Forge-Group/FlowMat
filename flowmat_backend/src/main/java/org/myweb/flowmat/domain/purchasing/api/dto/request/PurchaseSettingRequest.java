package org.myweb.flowmat.domain.purchasing.api.dto.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.math.BigDecimal;
import java.util.List;

/**
 * A project's purchasing settings, sent whole (docs/domain/purchasing.md PO33, PO50).
 *
 * @param approvalLimit   empty when no order needs the owner's approval
 * @param expectedVersion the version the form was opened at; empty when the project had none
 * @param approvalSteps   the members who approve from a value up (PO50); left out, they stay as they are
 */
public record PurchaseSettingRequest(String projectId, BigDecimal approvalLimit,
                                     @JsonDeserialize(using = VersionDeserializer.class) Long expectedVersion, List<Step> approvalSteps) {

    /** A member who approves orders worth fromValue or more. */
    public record Step(BigDecimal fromValue, String approverId) {
    }
}
