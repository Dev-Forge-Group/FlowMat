package org.myweb.flowmat.domain.workflow.api.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.math.BigDecimal;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessIo;

public record ProcessIoResponse(
    String processIoId,
    String processId,
    String itemId,
    String ioName,
    String direction,
    String ioType,
    BigDecimal quantity,
    String unit,
    String formula,
    String colorScheme,
    String requiredYn,
    String allowShortageYn,
    String role,
    String resourceType,
    JsonNode schemaJson,
    String validationRule
) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static ProcessIoResponse from(ProcessIo processIo) {
        return new ProcessIoResponse(
            processIo.getProcessIoId(), processIo.getProcessId(), processIo.getItemId(),
            processIo.getIoName(), processIo.getDirection(), processIo.getIoType(),
            processIo.getQuantity(), processIo.getUnit(), processIo.getFormula(),
            processIo.getColorScheme(), processIo.getRequiredYn(), processIo.getAllowShortageYn(),
            processIo.getRole(), processIo.getResourceType(), readSchema(processIo.getSchemaJson()),
            processIo.getValidationRule()
        );
    }

    private static JsonNode readSchema(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored port schema is invalid.", exception);
        }
    }
}
