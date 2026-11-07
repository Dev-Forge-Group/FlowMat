package org.myweb.flowmat.domain.connector.application.publicapi;

/** Caller checks project access. Every lookup rechecks the profile kill switch and current deployment target policy. */
public interface ConnectorProfileQuery {
    ConnectorProfileOperation requireOperation(String projectId, String profileVersionId, String operationId);
}
