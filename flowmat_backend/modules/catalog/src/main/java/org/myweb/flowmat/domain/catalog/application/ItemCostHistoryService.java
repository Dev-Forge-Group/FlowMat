package org.myweb.flowmat.domain.catalog.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.response.ItemCostChangeResponse;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.domain.entity.ItemCostHistory;
import org.myweb.flowmat.domain.catalog.repository.ItemCostHistoryRepository;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The unit cost changes of items (docs/domain/material-cost.md "단가 이력"). Every save of an item comes through
 * ItemServiceImpl, which tells this service the cost before and after; imports and the cost roll-up save items there too.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ItemCostHistoryService {

    private static final String NOT_DELETED = "N";

    private final ItemCostHistoryRepository historyRepository;
    private final ItemRepository itemRepository;
    private final ProjectAccessService projectAccessService;
    private final IdGenerator idGenerator;

    /** Keeps a change of the item's unit cost; nothing when it stayed the same (0 and none are both unknown). */
    @Transactional
    public void record(Item item, BigDecimal before, BigDecimal after) {
        BigDecimal was = known(before);
        BigDecimal now = known(after);
        if (was == null ? now == null : now != null && was.compareTo(now) == 0) {
            return;
        }
        ItemCostHistory change = new ItemCostHistory();
        change.setItemCostHistoryId(idGenerator.generate());
        change.setProjectId(item.getProjectId());
        change.setItemId(item.getItemId());
        change.setPreviousUnitCost(before);
        change.setUnitCost(after);
        change.setChangedBy(projectAccessService.requireCurrentUserId());
        change.setChangedAt(OffsetDateTime.now());
        historyRepository.save(change);
    }

    /** The item's unit cost changes, newest first. */
    public List<ItemCostChangeResponse> history(String itemId) {
        Item item = itemRepository.findByItemIdAndDeletedYn(itemId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectReadAccess(item.getProjectId());
        return historyRepository.findAllByItemIdOrderByChangedAtDesc(item.getItemId()).stream()
            .map(change -> new ItemCostChangeResponse(change.getPreviousUnitCost(), change.getUnitCost(), change.getChangedBy(),
                change.getChangedAt()))
            .toList();
    }

    private static BigDecimal known(BigDecimal cost) {
        return cost == null || cost.signum() == 0 ? null : cost;
    }
}
