package org.myweb.flowmat.domain.production.repository;

import java.util.List;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductionRunItemRepository extends JpaRepository<ProductionRunItem, String> {

    List<ProductionRunItem> findAllByProductionRunIdOrderByProductionRunItemIdAsc(String productionRunId);

    /** Recordings of one LOT in one direction across runs, e.g. the runs that consumed it. */
    List<ProductionRunItem> findAllByLotIdAndDirection(String lotId, String direction);

    /** Consumers backed by a stock movement that still stands, including erroneous legacy simulation movements. */
    @Query("""
        select distinct item.productionRunId from ProductionRunItem item
         where item.lotId = :lotId and item.direction = 'input' and item.cancelledYn <> 'Y'
           and exists (
               select movement.inventoryTransactionId from InventoryTransaction movement
                where movement.referenceType = 'production_run_item'
                  and movement.referenceId = item.productionRunItemId
                  and movement.transactionType = 'production_input' and movement.quantityDelta < 0
                  and not exists (
                      select reversal.inventoryTransactionId from InventoryTransaction reversal
                       where reversal.transactionType = 'reversal'
                         and reversal.referenceId = movement.inventoryTransactionId
                  )
           )
        """)
    List<String> findConsumingRunIdsByLotId(@Param("lotId") String lotId);

    /** LOT recordings whose matching, nonzero stock movement has not been reversed. */
    @Query("""
        select item from ProductionRunItem item
         where item.productionRunId = :runId and item.lotId is not null and item.cancelledYn <> 'Y'
           and exists (
               select movement.inventoryTransactionId from InventoryTransaction movement
                where movement.referenceType = 'production_run_item'
                  and movement.referenceId = item.productionRunItemId and movement.lotId = item.lotId
                  and ((item.direction = 'input' and movement.transactionType = 'production_input' and movement.quantityDelta < 0)
                    or (item.direction = 'output' and movement.transactionType = 'production_output' and movement.quantityDelta > 0))
                  and not exists (
                      select reversal.inventoryTransactionId from InventoryTransaction reversal
                       where reversal.transactionType = 'reversal'
                         and reversal.referenceId = movement.inventoryTransactionId
                  )
           )
         order by item.productionRunItemId
        """)
    List<ProductionRunItem> findLotRecordingsWithStandingMovements(@Param("runId") String runId);
}
