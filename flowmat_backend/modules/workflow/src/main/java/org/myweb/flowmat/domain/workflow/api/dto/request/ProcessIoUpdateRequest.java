package org.myweb.flowmat.domain.workflow.api.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record ProcessIoUpdateRequest(
    String itemId,
    Boolean clearItem,
    @Size(max = 100, message = "ioName must be at most 100 characters.") String ioName,
    String direction,
    @Size(max = 30, message = "ioType must be at most 30 characters.") String ioType,
    BigDecimal quantity,
    @Size(max = 20, message = "unit must be at most 20 characters.") String unit,
    String formula,
    @Size(max = 30, message = "colorScheme must be at most 30 characters.") String colorScheme,
    String requiredYn,
    String allowShortageYn,
    @Size(max = 50) String role,
    @Size(max = 50) String resourceType,
    JsonNode schemaJson,
    Boolean clearSchema,
    @Size(max = 2000) String validationRule,
    /** Empties the quantity and unit; one sent along is set again (docs/domain/port-measurement.md PM5). */
    Boolean clearMeasure
) {
}
