package org.myweb.flowmat.domain.connector.application.publicapi;

import com.fasterxml.jackson.databind.JsonNode;

/** Explicit, single HTTP dispatch. The caller owns authorization and durable attempt claiming. */
public interface ConnectorHttpGateway {
    ConnectorHttpOutcome execute(ConnectorProfileOperation operation, JsonNode requestBody);
}
