package org.myweb.flowmat.domain.bom.application;

import java.util.List;
import java.util.UUID;
import org.myweb.flowmat.domain.bom.api.dto.request.BomCreateRequest;
import org.myweb.flowmat.domain.bom.api.dto.request.BomLineCreateRequest;
import org.myweb.flowmat.domain.bom.api.dto.request.BomUpdateRequest;
import org.myweb.flowmat.domain.bom.api.dto.response.BomWhereUsedResponse;
import org.myweb.flowmat.domain.bom.application.publicapi.BomQuery;
import org.myweb.flowmat.domain.bom.application.publicapi.BomView;

/**
 * BOM revisions and their materials (docs/domain/inventory-bom-lot-contract.md §5). The reads other bounded contexts use
 * are in {@link BomQuery}.
 */
public interface BomService extends BomQuery {

    /** BOM revisions of the project that use the item as a line: approved first, then drafts, then retired. */
    List<BomWhereUsedResponse> whereUsed(String projectId, String itemId);

    BomView createBom(BomCreateRequest request);

    BomView updateBom(String bomId, BomUpdateRequest request);

    /** Soft-deletes a draft; submitted revisions are kept as history. */
    void deleteBom(String bomId);

    BomView addLine(String bomId, BomLineCreateRequest request);

    BomView deleteLine(String bomId, String bomLineId);

    /** Copies an approved or retired revision into a new draft with the next version number. */
    BomView createRevision(String bomId);

    /** With a requestId, repeating the same source/actor command returns the original created revision. */
    BomView createRevision(String bomId, UUID requestId);
}
