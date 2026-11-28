package org.myweb.flowmat.domain.workflow.api.dto.response;

/** Whether this server drafts processes from a description (ADR-047): off unless turned on and given a key. */
public record ProcessDraftAvailability(boolean available) {
}
