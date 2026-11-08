package org.myweb.flowmat.domain.inventory.application.publicapi;

/**
 * LOT changes another bounded context may ask for (docs/architecture/adr/ADR-002-module-dependency.md). Add an operation
 * only when a caller needs it.
 */
public interface LotReleaseCommand {

    /**
     * Releases a LOT that waits for its receipt checks; the caller has checked them (docs/domain/lot-release.md R3).
     * Answers the LOT's status afterwards. Not found when the LOT does not exist, conflict when it is not waiting.
     */
    String releaseAfterReceiptChecks(String lotId, String actorUserId);
}
