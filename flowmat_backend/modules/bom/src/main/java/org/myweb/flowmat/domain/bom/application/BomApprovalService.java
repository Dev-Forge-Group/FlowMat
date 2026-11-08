package org.myweb.flowmat.domain.bom.application;

import org.myweb.flowmat.domain.bom.application.publicapi.BomView;

/** BOM approval flow: draft → pending_approval → approved → retired, with reject back to draft. */
public interface BomApprovalService {

    BomView submit(String bomId);

    default BomView approve(String bomId, String note) {
        return approve(bomId, note, false);
    }

    /** {@code endEarlier}: end the approved revisions this one replaces the day before it starts (multi-level-bom.md M4-M6). */
    BomView approve(String bomId, String note, boolean endEarlier);

    BomView reject(String bomId, String note);

    BomView retire(String bomId, String note);
}
