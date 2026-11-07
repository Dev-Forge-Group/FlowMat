package org.myweb.flowmat.domain.flowrun.api.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Caller idempotency key only; targets, mappings and credentials come from the published binding. */
public record FlowRunHttpExecuteRequest(@NotNull UUID requestId) { }
