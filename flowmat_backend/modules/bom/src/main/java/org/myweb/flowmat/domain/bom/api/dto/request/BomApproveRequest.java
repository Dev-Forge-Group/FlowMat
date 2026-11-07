package org.myweb.flowmat.domain.bom.api.dto.request;

/**
 * Optional note for approve / reject / retire. The acting user comes from authentication, never from this body.
 *
 * @param endEarlier approve only: also end the approved revisions this one replaces the day before it starts
 *     (docs/domain/multi-level-bom.md M4-M6); never implied
 */
public record BomApproveRequest(String note, Boolean endEarlier) {
    public BomApproveRequest(String note) {
        this(note, null);
    }
}
