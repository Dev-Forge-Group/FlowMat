package org.myweb.flowmat.domain.production.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Creates a work order in {@code draft}; approval is a separate owner action. */
public record WorkOrderCreateRequest(
    @NotBlank String projectId,
    @NotBlank(message = "workOrderTitle is required.") String workOrderTitle,
    String workflowId,
    String bomId,
    String targetItemId,
    BigDecimal targetQuantity,
    String priority,
    OffsetDateTime plannedStartAt,
    OffsetDateTime plannedEndAt,
    String instruction,
    String assignedTo,
    /** Link to the written work instruction; http or https, at most 255 characters. */
    String instructionUrl,
    /** Optional BOM materials the order takes, by item (ADR-033); null or empty takes none. */
    List<String> optionalItemIds
) {

    public WorkOrderCreateRequest(String projectId, String workOrderTitle, String workflowId, String bomId, String targetItemId,
                                  BigDecimal targetQuantity, String priority, OffsetDateTime plannedStartAt,
                                  OffsetDateTime plannedEndAt, String instruction, String assignedTo, String instructionUrl) {
        this(projectId, workOrderTitle, workflowId, bomId, targetItemId, targetQuantity, priority, plannedStartAt, plannedEndAt,
            instruction, assignedTo, instructionUrl, null);
    }
}
