package org.myweb.flowmat.domain.catalog.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemRepository extends JpaRepository<Item, String> {

    List<Item> findAllByProjectIdAndDeletedYnOrderByCreatedAtAsc(String projectId, String deletedYn);

    Optional<Item> findByItemIdAndDeletedYn(String itemId, String deletedYn);

    /** Scalar fields preserve findAllById's scope, including deleted items, without reusing cached item state. */
    @Query("""
        select i.itemId as itemId, i.itemCode as itemCode, i.storageCondition as storageCondition
        from Item i where i.itemId in :itemIds
        """)
    List<StorageConditionFields> findStorageConditions(@Param("itemIds") Collection<String> itemIds);

    interface StorageConditionFields {
        String getItemId();
        String getItemCode();
        String getStorageCondition();
    }

    /** Whether an active item already uses the code; codes are kept unique per project by the service. */
    boolean existsByProjectIdAndItemCodeAndDeletedYn(String projectId, String itemCode, String deletedYn);

    /** Another active item of the project with this barcode, if any. */
    Optional<Item> findFirstByProjectIdAndBarcodeAndDeletedYnAndItemIdNot(String projectId, String barcode, String deletedYn, String itemId);
}
