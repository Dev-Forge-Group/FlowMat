package org.myweb.flowmat.domain.workflow.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessDraftRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessDraftAvailability;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessDraftResponse;
import org.myweb.flowmat.domain.workflow.application.ProcessDraftService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** A first process drafted from a description (docs/architecture/adr/ADR-047-process-draft-from-a-description.md). */
@RestController
@RequiredArgsConstructor
public class ProcessDraftController {

    private final ProcessDraftService service;

    /** Signed in; whether this server drafts at all, so the home screen shows the card only where it works. */
    @GetMapping("/process-drafts/availability")
    public ApiResponse<ProcessDraftAvailability> availability() {
        return ApiResponse.ok(new ProcessDraftAvailability(service.available()));
    }

    /** Signed in; 503 when the server has no drafting set up, 429 past the hourly limit, 502 when the model fails. */
    @PostMapping("/process-drafts")
    public ApiResponse<ProcessDraftResponse> draft(@Valid @RequestBody ProcessDraftRequest request) {
        return ApiResponse.ok(service.draft(request.description()));
    }
}
