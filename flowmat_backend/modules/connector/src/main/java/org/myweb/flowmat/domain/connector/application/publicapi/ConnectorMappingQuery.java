package org.myweb.flowmat.domain.connector.application.publicapi;

import com.fasterxml.jackson.databind.JsonNode;

/** Bounded JSON mappings for other modules, without network calls or stored state. */
public interface ConnectorMappingQuery {
    /** Validates a definition without resolving source references or applying runtime output budgets. */
    void validate(JsonNode mapping);

    /** Applies a definition to actual source data and returns an independent result within the output budgets. */
    JsonNode map(JsonNode source, JsonNode mapping);
}
