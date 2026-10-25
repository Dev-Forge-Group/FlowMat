package org.myweb.flowmat.domain.quality.application.publicapi;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Nonconformity reads for other bounded contexts, which use this instead of the quality repositories
 * (docs/architecture/adr/ADR-002-module-dependency.md). Add an operation only when a caller needs it.
 */
public interface NonconformityQuery {

    /** The nonconformity if it belongs to the project (a purchase return names it, docs/domain/purchasing.md PO42). */
    Optional<NcrView> findProjectNcr(String projectId, String nonconformityId);

    /** Closed nonconformities of the projects with a check due day and no check recorded yet (notification NS7). */
    List<NcrCheckView> findPendingChecks(Collection<String> projectIds);
}
