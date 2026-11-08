package org.myweb.flowmat.domain.inventory.application.publicapi;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * LOT reads for other bounded contexts, which use this instead of the inventory repositories
 * (docs/architecture/adr/ADR-002-module-dependency.md). Add an operation only when a caller needs it.
 */
public interface LotQuery {

    /** The LOT if it exists and belongs to the project. */
    Optional<LotView> findProjectLot(String projectId, String lotId);

    /** LOTs by id; ids that do not exist are left out. */
    Map<String, LotView> findLots(Collection<String> lotIds);
}
