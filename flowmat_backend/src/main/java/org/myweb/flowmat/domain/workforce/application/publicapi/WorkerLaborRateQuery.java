package org.myweb.flowmat.domain.workforce.application.publicapi;

import java.util.Collection;

/** Workers' hourly rates for labor cost (docs/architecture/adr/ADR-025-worker-labor-cost.md). Callers require the project owner. */
public interface WorkerLaborRateQuery {
    WorkerLaborRates rates(String projectId, Collection<String> userIds);
}
