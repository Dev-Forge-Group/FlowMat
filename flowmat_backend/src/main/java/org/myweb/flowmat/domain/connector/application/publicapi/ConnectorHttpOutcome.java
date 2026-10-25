package org.myweb.flowmat.domain.connector.application.publicapi;

import com.fasterxml.jackson.databind.JsonNode;

/** Response JSON is transient; only the binding's explicitly mapped output may be persisted. */
public record ConnectorHttpOutcome(String status, Integer httpStatus, JsonNode response,
                                   String errorCode, boolean mayHaveBeenSent) {
    @Override
    public String toString() {
        return "ConnectorHttpOutcome[status=" + status + ", httpStatus=" + httpStatus
            + ", errorCode=" + errorCode + ", mayHaveBeenSent=" + mayHaveBeenSent + "]";
    }
}
