package org.myweb.flowmat.domain.bom.application;

import org.myweb.flowmat.domain.bom.api.dto.response.BomResponse;

/** BOM approval flow: draft → pending_approval → approved → retired, with reject back to draft. */
public interface BomApprovalService {

    BomResponse submit(String bomId);

    default BomResponse approve(String bomId, String note) {
        return approve(bomId, note, false);
    }

    /** {@code endEarlier}: end the approved revisions this one replaces the day before it starts (multi-level-bom.md M4-M6). */
    BomResponse approve(String bomId, String note, boolean endEarlier);

    BomResponse reject(String bomId, String note);

    BomResponse retire(String bomId, String note);
}
