package org.myweb.flowmat.domain.inventory.repository;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryRepository extends JpaRepository<Inventory, String> {

    List<Inventory> findAllByProjectIdAndDeletedYnOrderByCreatedAtAsc(String projectId, String deletedYn);

    Optional<Inventory> findByInventoryIdAndDeletedYn(String inventoryId, String deletedYn);

    List<Inventory> findAllByLotIdAndDeletedYn(String lotId, String deletedYn);

    interface LotStockTotals {
        BigDecimal getQuantityOnHand();
        BigDecimal getQuantityReserved();
        long getStockRows();
        long getQuarantinedRows();
    }

    /** Scalar sums read committed quantities even when the caller has old Inventory entities cached. */
    @Query("""
        select coalesce(sum(i.quantity), 0) as quantityOnHand,
               coalesce(sum(i.reservedQuantity), 0) as quantityReserved,
               count(i) as stockRows,
               coalesce(sum(case when i.inventoryStatus = 'quarantined' then 1 else 0 end), 0) as quarantinedRows
          from Inventory i where i.lotId = :lotId and i.deletedYn = 'N'
        """)
    LotStockTotals lotStockTotals(@Param("lotId") String lotId);

    @Query("""
        select i.inventoryId from Inventory i
         where i.lotId = :lotId and i.deletedYn = 'N'
           and (i.inventoryStatus is null or i.inventoryStatus <> 'quarantined')
        """)
    List<String> findInventoryIdsToHoldForLot(@Param("lotId") String lotId);

    /** The active records of several LOTs at once (LOT recall). */
    List<Inventory> findAllByLotIdInAndDeletedYn(Collection<String> lotIds, String deletedYn);

    boolean existsByItemIdAndDeletedYn(String itemId, String deletedYn);

    /** The active row of one LOT at one place (unique by V17 uq_inventory_item_location_lot). */
    @Query("""
        select i from Inventory i
         where i.projectId = :projectId and i.itemId = :itemId and i.lotId = :lotId and i.deletedYn = 'N'
           and coalesce(i.location, '') = coalesce(:location, '')
        """)
    Optional<Inventory> findLotStockAt(
        @Param("projectId") String projectId,
        @Param("itemId") String itemId,
        @Param("lotId") String lotId,
        @Param("location") String location
    );

    /** Active rows of an item without LOT at one place, oldest first (nothing makes them unique). */
    @Query("""
        select i from Inventory i
         where i.projectId = :projectId and i.itemId = :itemId and i.lotId is null and i.deletedYn = 'N'
           and coalesce(i.location, '') = coalesce(:location, '')
         order by i.createdAt asc
        """)
    List<Inventory> findStockAt(
        @Param("projectId") String projectId,
        @Param("itemId") String itemId,
        @Param("location") String location
    );

    /**
     * Holds a lock on one item, LOT and place until the transaction ends, so two movements that would each create the
     * record there take turns: the second waits, then finds the record the first made. A hash collision only makes
     * unrelated movements wait for each other.
     */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    Integer lockStockPlace(@Param("key") String key);

    /** {@link #lockStockPlace(String)} for one item, LOT (or none) and place (or none), with the same key everywhere. */
    default void lockStockPlace(String projectId, String itemId, String lotId, String location) {
        lockStockPlace(String.join("|", "stock-place", projectId, itemId, lotId == null ? "" : lotId, location == null ? "" : location));
    }

    /**
     * Locks an item's active stock records until the transaction ends, in id order so two lockers cannot deadlock, and
     * returns their ids. Load the records after this call: a split planned from them (first-expiring first) then cannot
     * be overtaken by another movement between planning and applying.
     */
    @Query(value = """
        select inventory_id from inventory
         where project_id = :projectId and item_id = :itemId and deleted_yn = 'N'
         order by inventory_id
           for update
        """, nativeQuery = true)
    List<String> lockItemStock(@Param("projectId") String projectId, @Param("itemId") String itemId);

    /** Locks the row (deleted or not) so its stock alerts are worked out against numbers that cannot move meanwhile. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.inventoryId = :id")
    Optional<Inventory> findForUpdate(@Param("id") String inventoryId);

    /** Active rows that have a threshold to watch. */
    @Query("select i.inventoryId from Inventory i where i.deletedYn = 'N' and (i.minThreshold > 0 or i.maxThreshold is not null)")
    List<String> findIdsWithThresholds();

    /** Active rows holding stock of a LOT with an expiry date; their expiry alerts change with the date alone. */
    @Query("""
        select i.inventoryId from Inventory i, LotMaster l
         where i.lotId = l.lotId and i.deletedYn = 'N' and l.expiryDate is not null and i.quantity > 0
        """)
    List<String> findIdsHoldingDatedLots();

    /** Mirrors the V17 unique index uq_inventory_item_location_lot (a blank location counts as the same place). */
    @Query("""
        select count(i) > 0 from Inventory i
         where i.projectId = :projectId and i.itemId = :itemId and i.lotId = :lotId and i.deletedYn = 'N'
           and coalesce(i.location, '') = coalesce(:location, '')
        """)
    boolean existsLotStockAt(
        @Param("projectId") String projectId,
        @Param("itemId") String itemId,
        @Param("lotId") String lotId,
        @Param("location") String location
    );

    /**
     * Applies a stock movement in a single UPDATE so concurrent movements cannot overwrite each other, and only if the
     * result keeps the stock invariants (quantity >= 0, 0 <= reserved <= quantity, available = quantity - reserved).
     * Quarantined stock is skipped unless {@code allowQuarantined}.
     *
     * <p>Returns the number of rows changed: 0 means the record is gone, quarantined, or the movement would break an
     * invariant. Clears the persistence context; re-read the inventory afterwards to see the new values.
     */
    /**
     * Stamps records as counted. Nothing else changes, not even the version: a count that found the stock right should
     * not turn someone's open adjustment into a conflict.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Inventory i set i.lastCheckedAt = :at, i.lastCheckedBy = :by where i.inventoryId in :ids")
    int markChecked(@Param("ids") Collection<String> inventoryIds, @Param("at") OffsetDateTime at, @Param("by") String by);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update Inventory i
           set i.quantity = coalesce(i.quantity, 0) + :quantityDelta,
               i.reservedQuantity = coalesce(i.reservedQuantity, 0) + :reservedDelta,
               i.availableQuantity = (coalesce(i.quantity, 0) + :quantityDelta)
                                   - (coalesce(i.reservedQuantity, 0) + :reservedDelta),
               i.version = i.version + 1,
               i.updatedAt = :now
         where i.inventoryId = :inventoryId
           and i.deletedYn = 'N'
           and (:allowQuarantined = true or coalesce(i.inventoryStatus, 'available') <> 'quarantined')
           and coalesce(i.quantity, 0) + :quantityDelta >= 0
           and coalesce(i.reservedQuantity, 0) + :reservedDelta >= 0
           and coalesce(i.reservedQuantity, 0) + :reservedDelta <= coalesce(i.quantity, 0) + :quantityDelta
        """)
    int applyMovement(
        @Param("inventoryId") String inventoryId,
        @Param("quantityDelta") BigDecimal quantityDelta,
        @Param("reservedDelta") BigDecimal reservedDelta,
        @Param("allowQuarantined") boolean allowQuarantined,
        @Param("now") OffsetDateTime now
    );

    /** Sets the status in one UPDATE (bumping the version); returns 0 when the record is gone. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update Inventory i
           set i.inventoryStatus = :status,
               i.version = i.version + 1,
               i.updatedAt = :now
         where i.inventoryId = :inventoryId
           and i.deletedYn = 'N'
        """)
    int updateStatus(
        @Param("inventoryId") String inventoryId,
        @Param("status") String status,
        @Param("now") OffsetDateTime now
    );

    /**
     * Per lower-cased location: stock records holding stock (on hand or reserved) and their different items. Locations
     * are compared case-insensitively, as the location list matches them (docs/domain/storage-location.md).
     */
    @Query("""
        select lower(i.location), count(i), count(distinct i.itemId) from Inventory i
        where i.projectId = :projectId and i.deletedYn = 'N' and i.location is not null
          and (i.quantity <> 0 or i.reservedQuantity <> 0)
        group by lower(i.location)
        """)
    List<Object[]> summarizeHeldStockByLocation(@Param("projectId") String projectId);

    @Query("""
        select count(i) from Inventory i
        where i.projectId = :projectId and i.deletedYn = 'N' and lower(i.location) = lower(:location)
          and (i.quantity <> 0 or i.reservedQuantity <> 0)
        """)
    long countHeldStockAtLocation(@Param("projectId") String projectId, @Param("location") String location);

    /** Live stock records at a place by its code (ignoring case and spaces around it), with or without stock. */
    @Query("""
        select count(i) from Inventory i
        where i.projectId = :projectId and i.deletedYn = 'N' and lower(trim(i.location)) = lower(:location)
        """)
    long countRecordsAtLocation(@Param("projectId") String projectId, @Param("location") String location);

    /**
     * Moves every live stock record at a renamed place to its new code (docs/domain/storage-location.md L7). The version
     * goes up, so an edit or movement that read a record before the rename fails instead of writing the old code back.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
        update inventory set location = :code, version = coalesce(version, 0) + 1, updated_by = :userId, updated_at = now()
         where project_id = :projectId and deleted_yn = 'N' and lower(trim(location)) = lower(:previous)
        """, nativeQuery = true)
    int renameLocation(@Param("projectId") String projectId, @Param("previous") String previous, @Param("code") String code,
                       @Param("userId") String userId);

    @Query("""
        select count(distinct i.itemId) from Inventory i
        where i.projectId = :projectId and i.deletedYn = 'N' and lower(i.location) = lower(:location)
          and (i.quantity <> 0 or i.reservedQuantity <> 0)
        """)
    long countHeldItemsAtLocation(@Param("projectId") String projectId, @Param("location") String location);

    /** Every location text the project's live stock records name, empty or not. */
    @Query("""
        select distinct i.location from Inventory i
        where i.projectId = :projectId and i.deletedYn = 'N' and i.location is not null
        order by i.location
        """)
    List<String> findUsedLocations(@Param("projectId") String projectId);
}
