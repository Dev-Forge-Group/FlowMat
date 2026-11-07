package org.myweb.flowmat.domain.workflow.editor.api.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record EditorDocumentResponse(
    int schemaVersion,
    JsonNode camera,
    int nextElementSeq,
    int version,
    long versionNonce,
    List<EditorElementResponse> elements
) {
}
