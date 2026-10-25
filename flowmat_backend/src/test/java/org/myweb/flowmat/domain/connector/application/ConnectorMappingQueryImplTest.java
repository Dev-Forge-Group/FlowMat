package org.myweb.flowmat.domain.connector.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorMappingQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

class ConnectorMappingQueryImplTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConnectorMappingQuery query = new ConnectorMappingQueryImpl(new JsonMappingEngine(mapper));

    @Test
    void acceptsRequiredPointersAsDefinitionsAndRejectsTheirMissingValuesOnlyWhenMapping() throws Exception {
        JsonNode mapping = mapper.readTree("{\"from\":\"/input/required\"}");

        assertDoesNotThrow(() -> query.validate(mapping));
        BusinessException missing = assertThrows(BusinessException.class,
            () -> query.map(mapper.createObjectNode(), mapping));

        assertEquals(ErrorCode.BAD_REQUEST, missing.getErrorCode());
    }

    @Test
    void publicMappingKeepsExactNumbersAndProducesIndependentOutputContainers() throws Exception {
        BigDecimal exact = new BigDecimal("123456789.123456789000");
        ObjectNode source = mapper.createObjectNode().set("value", DecimalNode.valueOf(exact));
        JsonNode mapping = mapper.readTree("{\"object\":{\"value\":{\"from\":\"/value\"}}}");

        ObjectNode result = (ObjectNode) query.map(source, mapping);

        assertEquals(exact, result.path("value").decimalValue());
        result.put("other", true);
        assertFalse(source.has("other"));
        assertEquals(exact, source.path("value").decimalValue());
    }

    @Test
    void invalidPublicDefinitionsKeepSafeBadRequestErrors() throws Exception {
        JsonNode mapping = mapper.readTree("{\"from\":\"/private-marker~2\"}");

        BusinessException invalid = assertThrows(BusinessException.class, () -> query.validate(mapping));

        assertEquals(ErrorCode.BAD_REQUEST, invalid.getErrorCode());
        assertFalse(invalid.getMessage().contains("private-marker"));
    }
}
