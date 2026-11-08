package org.myweb.flowmat.domain.production.api.dto.request;

/**
 * A work instruction revision's text. {@code projectId} and {@code itemId} are read only when creating; {@code documentUrl}
 * is an http or https link to a drawing or a video, or blank.
 */
public record WorkInstructionRequest(
    String projectId,
    String itemId,
    String title,
    String body,
    String documentUrl,
    /** Runs cannot finish until the required steps are confirmed; left out, it stays as it is (off for a new one). */
    Boolean blocksFinish
) {
}
