package org.myweb.flowmat.domain.flowrun.api.dto.response;

/** Exact, mapped request at the time of inspection; no transmission or credential resolution occurs. */
public record FlowRunHttpRequestPreviewResponse(String profileVersionId, String operationId, String method,
    String target, boolean hasRequestBody, String requestJson) {
    @Override
    public String toString() {
        return "FlowRunHttpRequestPreviewResponse[profileVersionId=" + profileVersionId
            + ", operationId=" + operationId + ", method=" + method + ", hasRequestBody=" + hasRequestBody + "]";
    }
}
