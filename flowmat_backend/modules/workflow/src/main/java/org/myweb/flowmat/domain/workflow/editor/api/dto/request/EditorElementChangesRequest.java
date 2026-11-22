package org.myweb.flowmat.domain.workflow.editor.api.dto.request;

import jakarta.validation.Valid;
import java.util.List;

/**
 * Element changes to the drawing layer (docs/architecture/adr/ADR-042-drawing-layer-element-changes.md decision 1): each
 * adds an element (no {@code baseVersion}), replaces one edited from {@code baseVersion}, or deletes {@code deleteId}
 * edited from {@code baseVersion}. All are applied or none.
 */
public record EditorElementChangesRequest(
    List<@Valid Change> changes,
    Integer nextElementSeq
) {
    public record Change(
        @Valid EditorElementRequest element,
        String deleteId,
        Integer baseVersion
    ) {
    }
}
