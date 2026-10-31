package org.myweb.flowmat.domain.connector.application.publicapi;

import com.fasterxml.jackson.databind.JsonNode;

/** Optional cancellation capability; existing single-dispatch gateway adapters remain compatible. */
public interface CancellableConnectorHttpGateway extends ConnectorHttpGateway {
    ConnectorHttpOutcome execute(ConnectorProfileOperation operation, JsonNode requestBody,
                                 ConnectorHttpCancellation cancellation);
}
