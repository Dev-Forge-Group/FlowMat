package org.myweb.flowmat.domain.quality.api.dto.response;

/** A defect gathered on a nonconformity: which one, and whether it is still open (docs/domain/nonconformity.md). */
public record NonconformityDefectLinkResponse(
    String defectLogId,
    String nonconformityId,
    String ncrNo,
    /** open or closed; a cancelled nonconformity lets its defects go. */
    String status
) {
}
