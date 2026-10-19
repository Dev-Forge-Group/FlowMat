package org.myweb.flowmat.domain.production.api.dto.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.UUID;
import org.myweb.flowmat.global.util.WholeNumberDeserializer;

/**
 * Time a person worked on a run (docs/domain/worker-calendar.md LB2).
 *
 * @param requestId     the client's key; sending the same record again returns the run as it is now
 * @param userId        who worked, a project member or its owner; the caller when left out
 * @param workedMinutes whole minutes, 1 to 10080 (7 days)
 * @param note          optional, at most 500 characters
 * @param stepId        a process step of the run it was worked on (LB13); the whole run when left out
 */
public record RunLaborRequest(UUID requestId, String userId,
                              @JsonDeserialize(using = WholeNumberDeserializer.class) Integer workedMinutes, String note,
                              String stepId) {

    /** Worked time on the whole run, as callers sent it before V98. */
    public RunLaborRequest(UUID requestId, String userId, Integer workedMinutes, String note) {
        this(requestId, userId, workedMinutes, note, null);
    }
}
