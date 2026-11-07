package org.myweb.flowmat.domain.workflow.editor.api.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record EditorDocumentSaveRequest(
    @NotNull Integer expectedVersion,
    Integer schemaVersion,
    JsonNode camera,
    Integer nextElementSeq,
    @NotNull List<@Valid EditorElementRequest> elements
) {
    public EditorDocumentSaveRequest(
        Integer schemaVersion,
        JsonNode camera,
        Integer nextElementSeq,
        List<EditorElementRequest> elements
    ) {
        this(null, schemaVersion, camera, nextElementSeq, elements);
    }
}
