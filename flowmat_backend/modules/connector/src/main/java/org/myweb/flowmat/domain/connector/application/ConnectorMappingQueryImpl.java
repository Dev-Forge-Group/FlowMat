package org.myweb.flowmat.domain.connector.application;

import com.fasterxml.jackson.databind.JsonNode;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorMappingQuery;
import org.springframework.stereotype.Component;

@Component
final class ConnectorMappingQueryImpl implements ConnectorMappingQuery {
    private final JsonMappingEngine engine;

    ConnectorMappingQueryImpl(JsonMappingEngine engine) {
        this.engine = engine;
    }

    @Override
    public void validate(JsonNode mapping) {
        engine.validate(mapping);
    }

    @Override
    public JsonNode map(JsonNode source, JsonNode mapping) {
        return engine.map(source, mapping);
    }
}
