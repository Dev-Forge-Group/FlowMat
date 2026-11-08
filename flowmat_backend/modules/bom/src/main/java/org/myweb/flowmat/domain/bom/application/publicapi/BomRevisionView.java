package org.myweb.flowmat.domain.bom.application.publicapi;

import org.myweb.flowmat.domain.bom.domain.enums.BomStatus;

/**
 * What another bounded context may read about one BOM revision: its project, the item it makes, its name and version, and
 * its status (draft, approved, retired; docs/domain/multi-level-bom.md).
 */
public record BomRevisionView(String bomId, String projectId, String targetItemId, String bomName, Integer bomVersion, String bomStatus) {

    /** Whether the revision is approved. */
    public boolean approved() {
        return BomStatus.APPROVED.code().equals(bomStatus);
    }

    /** Whether the revision is retired. */
    public boolean retired() {
        return BomStatus.RETIRED.code().equals(bomStatus);
    }
}
