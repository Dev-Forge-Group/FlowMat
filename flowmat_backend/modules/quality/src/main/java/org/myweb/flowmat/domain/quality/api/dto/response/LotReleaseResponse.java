package org.myweb.flowmat.domain.quality.api.dto.response;

/** A LOT released after its receipt checks, with its status now (docs/domain/lot-release.md). */
/** {@code checkedStandards}: required receipt standards whose latest result passed; 0 is a release without checks (R7). */
public record LotReleaseResponse(String lotId, String lotNo, String lotStatus, int checkedStandards) {
}
