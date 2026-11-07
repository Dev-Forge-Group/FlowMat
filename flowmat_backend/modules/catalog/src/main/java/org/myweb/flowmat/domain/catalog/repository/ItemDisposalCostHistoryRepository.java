package org.myweb.flowmat.domain.catalog.repository;

import java.util.Collection;
import java.util.List;
import org.myweb.flowmat.domain.catalog.domain.entity.ItemDisposalCostHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemDisposalCostHistoryRepository extends JpaRepository<ItemDisposalCostHistory, String> {
    List<ItemDisposalCostHistory> findAllByProjectIdAndItemIdInOrderByChangedAtDescItemDisposalCostHistoryIdDesc(
        String projectId, Collection<String> itemIds);

    List<ItemDisposalCostHistory> findTop50ByItemIdOrderByChangedAtDescItemDisposalCostHistoryIdDesc(String itemId);
}
