package org.myweb.flowmat.domain.integration.api.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A run of a job, or, from a preview, what a run would do (no id, nothing saved).
 *
 * @param status running, succeeded, partial, failed or outcome_unknown; for a preview, what a run would end as
 * @param items one per record the source sent, in its order
 */
public record IntegrationRunResponse(String runId, String jobId, String trigger, OffsetDateTime scheduledFor, String status,
                                     String runBy, OffsetDateTime startedAt, OffsetDateTime finishedAt, Integer rowsTotal,
                                     Integer rowsApplied, Integer rowsLeftOut, String message, List<Item> items) {

    /**
     * @param no the record's place in the source, from 1
     * @param key what names it, such as an item code
     * @param result what happened: create, update, unchanged, receive, or left_out with the reason
     */
    public record Item(int no, String key, String result, String message) {
    }
}
