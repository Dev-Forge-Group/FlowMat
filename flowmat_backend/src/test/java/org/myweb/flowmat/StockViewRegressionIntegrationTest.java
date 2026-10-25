package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotView;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockView;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockViewQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The public stock, LOT and item views keep exact values and the caller's row-lock semantics, as the removed Facts did
 * (docs/architecture/adr/ADR-018-entity-boundary.md, 2gq); fixtures are disposable.
 */
class StockViewRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired StockViewQuery stocks;
    @Autowired CatalogQuery catalog;
    @Autowired LotQuery lots;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManagerFactory entities;

    @Test
    void aPreviouslyReadStockIsRefreshedAfterAnotherTransactionCommits() {
        String item=item(); String id=stock(item,"10");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertEquals(new BigDecimal("10.0000"), stocks.findActiveStock(id).orElseThrow().quantity());
            otherTransaction(() -> jdbc.update("update inventory set quantity=5,reserved_quantity=2,available_quantity=3,version=version+1 where inventory_id=?",id));
            StockView locked=stocks.lockItemStock(DEMO_PROJECT,item).getFirst();
            assertEquals(new BigDecimal("5.0000"),locked.quantity());
            assertEquals(new BigDecimal("2.0000"),locked.reservedQuantity());
            assertEquals(new BigDecimal("3.0000"),locked.availableQuantity());
        });
    }

    @Test
    void aChangeWithoutAVersionBumpIsAlsoVisibleInTheLockedView() {
        String item=item();String id=stock(item,"10");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertEquals("available",stocks.findActiveStock(id).orElseThrow().inventoryStatus());
            otherTransaction(() -> jdbc.update("update inventory set inventory_status='quarantined',location='MOVED' where inventory_id=?",id));
            StockView locked=stocks.lockItemStock(DEMO_PROJECT,item).getFirst();
            assertEquals("quarantined",locked.inventoryStatus());
            assertEquals("MOVED",locked.location());
        });
    }

    @Test
    @Timeout(45)
    void theLockStaysHeldUntilTheCallersTransactionCommits() throws Exception {
        String item=item();String id=stock(item,"10");var pool=Executors.newSingleThreadExecutor();
        try {
            var waiting=new TransactionTemplate(transactions).execute(tx -> {
                stocks.lockItemStock(DEMO_PROJECT,item);
                int blocker=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                var writer=pool.submit(() -> jdbc.update("update inventory set quantity=9,available_quantity=9,version=version+1 where inventory_id=?",id));
                DatabaseContention.awaitWaitingOrDone(jdbc,writer,blocker);
                assertFalse(writer.isDone(),"A competing stock write must wait for the caller to commit.");
                return writer;
            });
            assertNotNull(waiting);assertEquals(1,waiting.get(10,TimeUnit.SECONDS));
            assertEquals(new BigDecimal("9.0000"),stocks.findActiveStock(id).orElseThrow().quantity());
        } finally {pool.shutdownNow();}
    }

    @Test
    void lockingWithoutACallerTransactionIsRejected() {
        assertThrows(IllegalTransactionStateException.class,() -> stocks.lockItemStock(DEMO_PROJECT,UUID.randomUUID().toString()));
    }

    @Test
    void decimalsMicrosecondInstantsCalendarDatesAndNullableFieldsAreKept() {
        String item=item();String id=stock(item,"9999999999.9999");
        jdbc.update("update item set unit_cost=9999999999.9999,conversion_rate=1.12345678 where item_id=?",item);
        jdbc.update("update inventory set created_at='2030-02-01T10:02:03.123456+09:00' where inventory_id=?",id);
        CatalogItemView read=catalog.findActiveItem(item).orElseThrow();
        assertEquals(new BigDecimal("9999999999.9999"),read.unitCost());
        assertEquals(new BigDecimal("1.12345678"),read.conversionRate());assertNull(read.leadTimeDays());
        StockView row=stocks.findActiveStock(id).orElseThrow();
        assertEquals(new BigDecimal("9999999999.9999"),row.quantity());
        assertEquals(Instant.parse("2030-02-01T01:02:03.123456Z"),row.createdAt().toInstant());assertNull(row.lotId());
        String lot=UUID.randomUUID().toString();
        jdbc.update("insert into lot_master(lot_id,project_id,item_id,lot_no,expiry_date,received_at) values(?,?,?,?,'2032-02-29','2030-02-01T01:02:03.123456Z')",lot,DEMO_PROJECT,item,"VIEW-"+lot.substring(0,8));
        LotView batch=lots.findLots(List.of(lot)).get(lot);
        assertEquals(LocalDate.of(2032,2,29),batch.expiryDate());
        assertEquals(row.createdAt().toInstant(),batch.receivedAt().toInstant());
        assertFalse(batch.isExpiredOn(LocalDate.of(2032,2,29)));assertTrue(batch.isExpiredOn(LocalDate.of(2032,3,1)));
        assertTrue(stocks.findActiveStock("missing").isEmpty());assertTrue(catalog.findActiveItem("missing").isEmpty());
        assertTrue(stocks.findStocks(List.of()).isEmpty());assertTrue(lots.findLots(List.of()).isEmpty());
    }

    @Test
    void freshLockedStocksUseTwoQueriesRegardlessOfBatchSize() {
        String item=item();for(int i=0;i<12;i++)stock(item,"10");
        long queries=statementCount(() -> {
            var locked=stocks.lockItemStock(DEMO_PROJECT,item);
            assertEquals(12,locked.size());locked.forEach(view -> assertEquals(new BigDecimal("10.0000"),view.quantity()));
        });
        assertTrue(queries>=1 && queries<=2,"Fresh locked views must stay a measured bulk read: "+queries);
    }

    @Test
    void itemAndStockBatchesStayBulkReadsAndAReadViewCannotChange() {
        var itemIds=new ArrayList<String>();var stockIds=new ArrayList<String>();
        for(int i=0;i<12;i++){String item=item();itemIds.add(item);stockIds.add(stock(item,"10"));}
        StockView frozen=stocks.findActiveStock(stockIds.getFirst()).orElseThrow();
        jdbc.update("update inventory set quantity=9,available_quantity=9,version=version+1 where inventory_id=?",stockIds.getFirst());
        assertEquals(new BigDecimal("10.0000"),frozen.quantity());
        // Separate transactions preserve the same cold first-level cache for each sample.
        long one=statementCount(() -> assertEquals(1,catalog.findItems(itemIds.subList(0,1)).size()));
        long twelve=statementCount(() -> assertEquals(12,catalog.findItems(itemIds).size()));
        assertTrue(one>=1,"The item measurement must include its database query.");
        assertEquals(one,twelve,"Bulk item views must not become N+1: "+one+" for 1 item, "+twelve+" for 12");
        long stockQueries=statementCount(() -> assertEquals(12,stocks.findStocks(stockIds).size()));
        assertEquals(1L,stockQueries,"Bulk stock views must use one measured query: "+stockQueries);
    }

    private long statementCount(Runnable read) {
        return new TransactionTemplate(transactions).execute(tx -> {
            try (SessionStatementCounter counter = SessionStatementCounter.start(entities)) {
                read.run();
                return counter.count();
            }
        });
    }

    private void otherTransaction(Runnable command) {
        var other=new TransactionTemplate(transactions);other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);other.executeWithoutResult(tx -> command.run());
    }
    private String item(){String id=UUID.randomUUID().toString();jdbc.update("insert into item(item_id,project_id,item_code,item_name,unit_id) values(?,?,?,?,?)",id,DEMO_PROJECT,"VIEW-"+id.substring(0,8),"View regression","unit_ea");return id;}
    private String stock(String item,String quantity){String id=UUID.randomUUID().toString();jdbc.update("insert into inventory(inventory_id,project_id,item_id,quantity,reserved_quantity,available_quantity,inventory_status,location) values(?,?,?,cast(? as numeric),0,cast(? as numeric),'available',?)",id,DEMO_PROJECT,item,quantity,quantity,"VIEW-"+id.substring(0,8));return id;}
}
