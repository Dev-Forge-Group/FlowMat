package org.myweb.flowmat.domain.integration.api.dto.request;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A job to make or change (docs/architecture/adr/ADR-038-integration-jobs.md).
 *
 * @param kind pull_items or pull_stock (push_stock comes later)
 * @param profileVersionId the connector profile version whose operation the job uses
 * @param operationId the approved operation that reads (pull)
 * @param mapping {"rowsAt": a JSON pointer to the array of records in the response, "row": a connector mapping whose
 *                object fields are the import's fields}
 * @param note pull_stock: the note on receipts into existing records
 * @param version the version the change was made on; required to change a job
 */
public record IntegrationJobRequest(String name, String kind, String profileVersionId, String operationId,
                                    String verifyOperationId, JsonNode mapping, String note, Integer version) {
}
