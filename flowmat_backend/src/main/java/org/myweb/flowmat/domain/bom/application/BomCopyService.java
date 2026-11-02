package org.myweb.flowmat.domain.bom.application;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.api.dto.request.BomCopyRequest;
import org.myweb.flowmat.domain.bom.api.dto.request.BomCreateRequest;
import org.myweb.flowmat.domain.bom.api.dto.request.BomLineCreateRequest;
import org.myweb.flowmat.domain.bom.api.dto.response.BomLineResponse;
import org.myweb.flowmat.domain.bom.api.dto.response.BomResponse;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copies a BOM to another product as that product's first draft (docs/domain/inventory-bom-lot-contract.md §5 "BOM
 * 복사"): same base and materials, in the same order. It goes through the ordinary create and add-line, so a product
 * that already has a BOM is refused there (make a revision of that one instead). The copy is a draft that needs its own
 * approval.
 */
@Service
@RequiredArgsConstructor
public class BomCopyService {

    private final BomService bomService;

    @Transactional
    public BomResponse copy(String bomId, BomCopyRequest request) {
        BomResponse source = bomService.getBom(bomId);
        String target = request.targetItemId().trim();
        if (target.equals(source.targetItemId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Pick another product; for this one make a new revision.");
        }
        if (source.lines().stream().anyMatch(line -> target.equals(line.childItemId()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "That product is a material of this BOM, so it cannot be made from it.");
        }
        String name = request.bomName() == null || request.bomName().isBlank() ? source.bomName() : request.bomName().trim();
        BomResponse copy = bomService.createBom(new BomCreateRequest(source.projectId(), target, name, source.baseQuantity(),
            source.baseUnit(), "Copied from " + source.bomName() + " v" + source.bomVersion()));
        for (BomLineResponse line : source.lines()) {
            copy = bomService.addLine(copy.bomId(), new BomLineCreateRequest(line.childItemId(), line.quantity(), line.unit(),
                line.scrapRate(), line.optionalYn(), line.substituteGroup(), line.sortOrder(), line.note(), line.lineType(),
                line.phantom(), null, line.priority()));
        }
        return copy;
    }
}
