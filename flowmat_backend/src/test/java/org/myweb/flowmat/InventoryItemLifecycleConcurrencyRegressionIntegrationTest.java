package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.myweb.flowmat.domain.catalog.api.dto.request.ItemUpdateRequest;
import org.myweb.flowmat.domain.catalog.application.ItemService;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryAdjustRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.StockImportRequest;
import org.myweb.flowmat.domain.inventory.application.InventoryService;
import org.myweb.flowmat.domain.inventory.application.StockImportService;
import org.myweb.flowmat.domain.project.application.ProjectService;
import org.myweb.flowmat.domain.project.api.dto.request.ProjectCreateRequest;
import org.myweb.flowmat.domain.purchasing.application.PurchaseOrderService;
import org.myweb.flowmat.domain.purchasing.api.dto.request.PurchaseOrderRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.request.PurchaseReceiptRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.response.PurchaseOrderResponse;
import org.myweb.flowmat.domain.rule.application.publicapi.FlowRuleEngineService;
import org.myweb.flowmat.domain.rule.application.publicapi.RuleEvaluationContext;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** Both real transactions must produce a result allowed by the existing item and stock rules. */
@Timeout(120)
class InventoryItemLifecycleConcurrencyRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private InventoryService stocks;
    @Autowired private ItemService items;
    @Autowired private CatalogQuery catalog;
    @Autowired private StockImportService imports;
    @Autowired private ProjectService projects;
    @Autowired private PurchaseOrderService purchases;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtProvider jwt;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoSpyBean private FlowRuleEngineService rules;

    @Test
    void newStockAndItemDeletionCannotBothCommit() throws Exception {
        String item = item();
        Outcome outcome = competingWithStock(item, () -> items.deleteItem(item));
        assertThat(outcome.stock().saved() && outcome.change().saved())
            .as("receiving stock and deleting its item cannot both commit").isFalse();
        if (outcome.stock().saved()) {
            assertThat(outcome.change().error()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(text(item, "deleted_yn")).isEqualTo("N");
            assertRows(item, 1);
        } else {
            assertThat(outcome.change().saved()).isTrue();
            assertThat(outcome.stock().error()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(text(item, "deleted_yn")).isEqualTo("Y");
            assertRows(item, 0);
        }
    }

    @Test
    void newStockAndSwitchingLotTrackingCannotBothCommit() throws Exception {
        String item = item();
        ItemUpdateRequest update = mapper.convertValue(Map.of("lotManageYn", "Y"), ItemUpdateRequest.class);
        Outcome outcome = competingWithStock(item, () -> items.updateItem(item, update));
        assertThat(outcome.stock().saved() && outcome.change().saved())
            .as("a LOT tracking change cannot leave newly received untracked stock").isFalse();
        if (outcome.stock().saved()) {
            assertThat(outcome.change().error()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(text(item, "lot_manage_yn")).isEqualTo("N");
            assertRows(item, 1);
        } else {
            assertThat(outcome.change().saved()).isTrue();
            assertThat(outcome.stock().error()).isEqualTo(ErrorCode.BAD_REQUEST);
            assertThat(text(item, "lot_manage_yn")).isEqualTo("Y");
            assertRows(item, 0);
        }
    }

    @Test
    void aWriterAlreadyHoldingTheItemReturnsAConflictWithoutStockWrites() throws Exception {
        String item = item();
        Result result = whileItemWriteLocked(item, () -> asOwner(() -> stocks.createInventory(stockRequest(DEMO_PROJECT, item))));
        assertThat(result.error()).isEqualTo(ErrorCode.CONFLICT);
        assertRows(item, 0);
    }

    @Test
    void twoStockCreationsCanHoldTheSameItemAtOnce() throws Exception {
        String item = item();
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> creator = new AtomicReference<>();
        var delegated = mockingDetails(rules).getMockCreationSettings().getDefaultAnswer();
        doAnswer(call -> {
            Object answer = delegated.answer(call);
            if (Thread.currentThread() == creator.get()) {
                checked.countDown();
                assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return answer;
        }).when(rules).validateRules(any(RuleEvaluationContext.class));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                creator.set(Thread.currentThread());
                return asOwner(() -> stocks.createInventory(stockRequest(DEMO_PROJECT, item)));
            });
            assertThat(checked.await(20, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> asOwner(() -> stocks.createInventory(stockRequest(DEMO_PROJECT, item))));
            try {
                assertThat(second.get(15, TimeUnit.SECONDS).saved()).isTrue();
            } finally {
                release.countDown();
            }
            assertThat(first.get(15, TimeUnit.SECONDS).saved()).isTrue();
            assertRows(item, 2);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void aConflictOnTheSecondImportedItemRollsBackTheFirstStockAndNewLot() throws Exception {
        String project = project();
        String first = item(project, false);
        String second = item(project, true);
        String lot = "LIFE-LOT-" + UUID.randomUUID();
        StockImportRequest request = mapper.convertValue(Map.of("projectId", project, "dryRun", false, "rows", List.of(
            Map.of("itemCode", first, "quantity", "1"), Map.of("itemCode", second, "quantity", "1", "lotNo", lot))),
            StockImportRequest.class);
        Result result = whileItemWriteLocked(second, () -> asOwner(() -> imports.importStock(request)));
        assertThat(result.error()).isEqualTo(ErrorCode.CONFLICT);
        assertRows(first, 0);
        assertRows(second, 0);
        assertThat(jdbc.queryForObject("select count(*) from lot_master where project_id=?", Integer.class, project)).isZero();
    }

    @Test
    void aConflictOnTheSecondPurchaseLineRollsBackReceiptsAndEarlierStock() throws Exception {
        String project = project();
        String first = item(project, false);
        String second = item(project, true);
        AtomicReference<PurchaseOrderResponse> ordered = new AtomicReference<>();
        PurchaseOrderRequest draft = mapper.convertValue(Map.of("projectId", project, "supplierName", "Lifecycle supplier", "lines", List.of(
            Map.of("itemId", first, "quantity", 2), Map.of("itemId", second, "quantity", 2))), PurchaseOrderRequest.class);
        assertThat(asOwner(() -> {
            String orderId = purchases.create(draft).purchaseOrderId();
            purchases.order(orderId);
            ordered.set(purchases.get(orderId));
        }).saved()).isTrue();
        PurchaseOrderResponse before = ordered.get();
        PurchaseReceiptRequest receipt = new PurchaseReceiptRequest(UUID.randomUUID(), List.of(
            new PurchaseReceiptRequest.Line(before.lines().get(0).lineId(), BigDecimal.ONE, null, null, null),
            new PurchaseReceiptRequest.Line(before.lines().get(1).lineId(), BigDecimal.ONE, null, "LIFE-LOT-" + UUID.randomUUID(), null)));
        Result result = whileItemWriteLocked(second, () -> asOwner(() -> purchases.receive(before.purchaseOrderId(), receipt)));
        assertThat(result.error()).isEqualTo(ErrorCode.CONFLICT);
        AtomicReference<PurchaseOrderResponse> after = new AtomicReference<>();
        assertThat(asOwner(() -> after.set(purchases.get(before.purchaseOrderId()))).saved()).isTrue();
        assertThat(after.get()).isEqualTo(before);
        assertRows(first, 0);
        assertRows(second, 0);
        assertThat(jdbc.queryForObject("select count(*) from purchase_receipt where purchase_order_id=?", Integer.class,
            before.purchaseOrderId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from lot_master where project_id=?", Integer.class, project)).isZero();
    }

    @Test
    void theGuardRequiresTheCallersTransaction() {
        assertThatThrownBy(() -> catalog.lockProjectItemForStock(DEMO_PROJECT, item()))
            .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theGuardRefreshesTheItemAlreadyCachedByItsCaller() throws Exception {
        String item = item();
        var pool = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                assertThat(catalog.findProjectItem(DEMO_PROJECT, item).orElseThrow().lotManageYn()).isEqualTo("N");
                try {
                    assertThat(pool.submit(() -> asOwner(() -> items.updateItem(item,
                        mapper.convertValue(Map.of("lotManageYn", "Y"), ItemUpdateRequest.class))))
                        .get(15, TimeUnit.SECONDS).saved()).isTrue();
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
                assertThat(catalog.lockProjectItemForStock(DEMO_PROJECT, item).orElseThrow().lotManageYn()).isEqualTo("Y");
            });
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private <T> T whileItemWriteLocked(String item, Callable<T> operation) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("select item_id from item where item_id=? for update", String.class, item);
                locked.countDown();
                try {
                    assertThat(release.await(40, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            }));
            assertThat(locked.await(15, TimeUnit.SECONDS)).isTrue();
            T result;
            try {
                result = pool.submit(operation).get(20, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
            return result;
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private InventoryAdjustRequest stockRequest(String project, String item) {
        return mapper.convertValue(Map.of("projectId", project, "itemId", item, "quantity", 1), InventoryAdjustRequest.class);
    }

    private String project() {
        AtomicReference<String> result = new AtomicReference<>();
        assertThat(asOwner(() -> result.set(projects.createProject(new ProjectCreateRequest("Lifecycle test", DEMO_OWNER, null, null))
            .projectId())).saved()).isTrue();
        return result.get();
    }

    private Outcome competingWithStock(String item, Runnable change) throws Exception {
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch changing = new CountDownLatch(1);
        AtomicReference<Thread> creator = new AtomicReference<>();
        AtomicReference<Integer> changingPid = new AtomicReference<>();
        var delegated = mockingDetails(rules).getMockCreationSettings().getDefaultAnswer();
        doAnswer(call -> {
            Object answer = delegated.answer(call);
            RuleEvaluationContext context = call.getArgument(0);
            if (Thread.currentThread() == creator.get() && "inventory_create".equals(context.facts().get("operation"))) {
                checked.countDown();
                assertThat(release.await(20, TimeUnit.SECONDS)).as("the stock request was released").isTrue();
            }
            return answer;
        }).when(rules).validateRules(any(RuleEvaluationContext.class));
        InventoryAdjustRequest request = mapper.convertValue(Map.of("projectId", DEMO_PROJECT, "itemId", item, "quantity", 1),
            InventoryAdjustRequest.class);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var stock = pool.submit(() -> {
                creator.set(Thread.currentThread());
                return asOwner(() -> stocks.createInventory(request));
            });
            if (!checked.await(20, TimeUnit.SECONDS)) {
                if (stock.isDone()) stock.get(1, TimeUnit.SECONDS);
                throw new AssertionError("The stock request did not complete its real validation.");
            }
            var edit = pool.submit(() -> asOwner(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                changingPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                changing.countDown();
                change.run();
            })));
            assertThat(changing.await(10, TimeUnit.SECONDS)).isTrue();
            boolean waiting = false;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!edit.isDone() && !waiting && System.nanoTime() < until) {
                waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where pid=? and wait_event_type='Lock'",
                    Integer.class, changingPid.get()) > 0;
                if (!waiting) Thread.sleep(20);
            }
            assertThat(edit.isDone() || waiting).as("the item change completed or reached a real database lock").isTrue();
            release.countDown();
            return new Outcome(stock.get(20, TimeUnit.SECONDS), edit.get(20, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Result asOwner(Runnable operation) {
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(jwt.getAuthentication(jwt.generateAccessToken(DEMO_OWNER)));
        SecurityContextHolder.setContext(context);
        try {
            operation.run();
            return new Result(true, null);
        } catch (BusinessException error) {
            return new Result(false, error.getErrorCode());
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private String item() {
        return item(DEMO_PROJECT, false);
    }

    private String item(String project, boolean lotTracked) {
        String id = "LIFE-" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("insert into item(item_id,project_id,item_code,item_name,item_status,lot_manage_yn,deleted_yn,unit_id) "
            + "values(?,?,?,'Lifecycle test','active',?,'N','unit_kg')", id, project, id, lotTracked ? "Y" : "N");
        return id;
    }

    private String text(String item, String field) {
        return jdbc.queryForObject("select " + field + " from item where item_id=?", String.class, item);
    }

    private void assertRows(String item, int expected) {
        assertThat(jdbc.queryForObject("select count(*) from inventory where item_id=?", Integer.class, item)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("select count(*) from inventory_transaction where item_id=?", Integer.class, item))
            .isEqualTo(expected);
    }

    private record Result(boolean saved, ErrorCode error) { }
    private record Outcome(Result stock, Result change) { }
}
