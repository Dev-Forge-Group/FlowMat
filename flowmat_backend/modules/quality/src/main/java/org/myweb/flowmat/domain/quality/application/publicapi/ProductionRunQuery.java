package org.myweb.flowmat.domain.quality.application.publicapi;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The production run reads quality needs to inspect runs and trace their LOTs. Production owns the runs and implements
 * this, so quality does not depend on production (production already depends on quality). Add an operation only when
 * quality needs it (docs/architecture/adr/ADR-002-module-dependency.md).
 */
public interface ProductionRunQuery {

    /** The run if it exists, is not deleted and belongs to the project. */
    Optional<ProductionRunView> findProjectRun(String projectId, String productionRunId);

    /** The run if it exists and is not deleted, whatever its project; the caller checks access. */
    Optional<ProductionRunView> findRun(String productionRunId);

    /** Runs by id, deleted ones included so old references still show a number; ids that do not exist are left out. */
    Map<String, ProductionRunView> findRuns(Collection<String> productionRunIds);

    /** The run's input and output recordings that are not cancelled, in recording order. */
    List<ProductionRunRecordingView> findRecordings(String productionRunId);
}
