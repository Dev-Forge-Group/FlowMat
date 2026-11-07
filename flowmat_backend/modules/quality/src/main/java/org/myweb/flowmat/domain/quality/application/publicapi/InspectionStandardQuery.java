package org.myweb.flowmat.domain.quality.application.publicapi;

import java.util.Collection;
import java.util.Map;

/**
 * Inspection standard reads for other bounded contexts, which use this instead of the quality repositories
 * (docs/architecture/adr/ADR-002-module-dependency.md). Add an operation only when a caller needs it.
 */
public interface InspectionStandardQuery {

    /** Standards by id, deleted ones too (not active); ids that never existed are left out. */
    Map<String, InspectionStandardView> findStandards(Collection<String> standardIds);
}
