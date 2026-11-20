package org.myweb.flowmat.domain.integration.api.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/** A job as its project sees it, with its latest run. Credentials are never part of a job. */
public record IntegrationJobResponse(String jobId, String projectId, String name, String kind, String profileVersionId,
                                     String operationId, String verifyOperationId, JsonNode mapping, String note,
                                     String scheduleCron, boolean scheduleEnabled, OffsetDateTime nextRunAt, String runAs,
                                     String pausedReason, int version, IntegrationRunResponse lastRun) {
}
