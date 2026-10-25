package org.myweb.flowmat.domain.bom.application.publicapi;

/**
 * What another bounded context may read about one BOM revision: its project, the item it makes, its name and version, and
 * its status (draft, approved, retired; docs/domain/multi-level-bom.md).
 */
public record BomRevisionView(String bomId, String projectId, String targetItemId, String bomName, Integer bomVersion, String bomStatus) {
}
