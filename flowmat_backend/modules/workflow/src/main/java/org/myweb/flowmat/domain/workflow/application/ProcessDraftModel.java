package org.myweb.flowmat.domain.workflow.application;

import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessDraftResponse;

/** Drafts a process from a description (ADR-047); there is none when drafting is not set up. */
public interface ProcessDraftModel {

    /** Thrown when the model declined or could not answer; its message is safe to show. */
    class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    ProcessDraftResponse draft(String description);
}
