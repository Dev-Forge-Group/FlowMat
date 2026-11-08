package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.util.List;

/**
 * What a recall quarantine did.
 *
 * @param quarantined LOT numbers put in quarantine now
 * @param skipped LOTs left as they were, and why
 */
public record LotRecallQuarantineResponse(List<String> quarantined, List<Skipped> skipped) {

    public record Skipped(String lotNo, String reason) {
    }
}
