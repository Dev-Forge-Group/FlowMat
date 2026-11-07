package org.myweb.flowmat.domain.catalog.application;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentSetupPreviewResponse;
import org.myweb.flowmat.domain.catalog.domain.entity.Equipment;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.repository.EquipmentChangeoverRepository;
import org.myweb.flowmat.domain.catalog.repository.EquipmentRepository;
import org.myweb.flowmat.domain.catalog.repository.EquipmentSetupChangeoverRepository;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EquipmentSetupPreviewService {
    private final EquipmentRepository equipment;
    private final ItemRepository items;
    private final EquipmentChangeoverService changeovers;
    private final EquipmentChangeoverRepository itemRules;
    private final EquipmentSetupChangeoverRepository attributeRules;
    private final ProjectAccessService access;

    /** Match and explanation must read the same committed snapshot, even if a rule is edited meanwhile. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EquipmentSetupPreviewResponse preview(String equipmentId, String fromItemId, String toItemId) {
        Equipment found = equipment.findByEquipmentIdAndDeletedYn(equipmentId, "N")
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectReadAccess(found.getProjectId());
        Item from = item(found, fromItemId, "fromItemId");
        Item to = item(found, toItemId, "toItemId");
        var match = changeovers.changeover(equipmentId, from.getItemId(), to.getItemId());
        if (match.isEmpty()) {
            return new EquipmentSetupPreviewResponse(equipmentId, from.getItemId(), to.getItemId(), 0, "NONE", null);
        }
        String id = match.get().changeoverId();
        String type;
        if (attributeRules.findById(id).filter(rule -> "N".equals(rule.getDeletedYn()) && equipmentId.equals(rule.getEquipmentId())).isPresent()) {
            type = "ATTRIBUTE_RULE";
        } else {
            var rule = itemRules.findByChangeoverIdAndDeletedYn(id, "N")
                .filter(one -> equipmentId.equals(one.getEquipmentId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "The selected changeover changed; reload the preview."));
            if (rule.getFromItemId() != null && rule.getToItemId() != null) type = "EXACT_ITEM_PAIR";
            else if (rule.getFromItemId() != null) type = "FROM_ITEM";
            else if (rule.getToItemId() != null) type = "TO_ITEM";
            else type = "DEFAULT";
        }
        return new EquipmentSetupPreviewResponse(equipmentId, from.getItemId(), to.getItemId(), match.get().minutes(), type, id);
    }

    private Item item(Equipment found, String id, String field) {
        if (id == null || id.isBlank() || id.trim().length() > 50) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " requires an item of this project.");
        }
        return items.findByItemIdAndDeletedYn(id.trim(), "N")
            .filter(one -> found.getProjectId().equals(one.getProjectId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, field + " requires a live item of this project."));
    }
}
