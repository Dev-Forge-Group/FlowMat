package org.myweb.flowmat.domain.inventory.api.dto.request;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** {@code quantity}: how much moves now; empty for all of the task (docs/domain/warehouse-task.md W6). */
public record WarehouseTaskCompleteRequest(BigDecimal quantity, @Size(max = 100) String expectedToLocation) {
}
