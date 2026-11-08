package org.myweb.flowmat.domain.bom.api.dto.request;

import java.util.UUID;

/** Optional for older clients. New clients send a UUID and retain it until the result is confirmed. */
public record BomRevisionRequest(UUID requestId) {}
