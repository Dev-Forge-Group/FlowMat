package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryAdjustRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryTransactionCreateRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.LotCreateRequest;
import org.myweb.flowmat.domain.inventory.application.InventoryService;
import org.myweb.flowmat.domain.inventory.application.InventoryTransactionService;
import org.myweb.flowmat.domain.inventory.application.LotServiceImpl;
import org.myweb.flowmat.domain.quality.application.LotReleaseService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real services and commit boundaries, using only IntegrationTestSupport's disposable databases. */
@Timeout(120)
class LotCloseStockConcurrencyRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private InventoryService stocks;
    @Autowired private InventoryTransactionService movements;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private EntityManager entities;
    @Autowired private LotReleaseService releases;
    @MockitoSpyBean private LotServiceImpl lots;

    @Test
    void closingALotAndReceivingNewStockCannotBothCommit() throws Exception {
        Fixture fixture = fixture(false);
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        CountDownLatch closing = new CountDownLatch(1);
        AtomicReference<Integer> closingPid = new AtomicReference<>();
        AtomicReference<Thread> creator = new AtomicReference<>();
        LotServiceImpl target = AopTestUtils.getUltimateTargetObject(lots);
        var delegated = mockingDetails(target).getMockCreationSettings().getDefaultAnswer();
        doAnswer(call -> {
            Object answer = delegated.answer(call);
            if (Thread.currentThread() == creator.get()) {
                checked.countDown();
                await(resume, "Resume the receipt after its real open-LOT validation");
            }
            return answer;
        }).when(target).requireLotForStock(fixture.lot(), DEMO_PROJECT, fixture.item());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Result received;
        Result closed;
        try {
            Future<Result> create = pool.submit(() -> {
                creator.set(Thread.currentThread());
                return asOwnerInTransaction(() -> stocks.createInventory(stockRequest(fixture, BigDecimal.ONE)));
            });
            awaitStarted(checked, create, "The receipt did not finish its real open-LOT validation.");
            Future<Result> close = pool.submit(() -> asOwnerInTransaction(() -> {
                closingPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                closing.countDown();
                lots.closeLot(fixture.lot());
            }));
            awaitStarted(closing, close, "The close transaction did not start.");
            boolean waiting = false;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!close.isDone() && !waiting && System.nanoTime() < until) {
                waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where pid=? and wait_event_type='Lock'",
                    Integer.class, closingPid.get()) > 0;
                if (!waiting) {
                    Thread.sleep(20);
                }
            }
            assertThat(close.isDone() || waiting).as("Close must finish or reach its database lock before receipt resumes").isTrue();
            resume.countDown();
            received = create.get(20, TimeUnit.SECONDS);
            closed = close.get(20, TimeUnit.SECONDS);
        } finally {
            resume.countDown();
            shutdown(pool);
        }
        assertSoftly(softly -> {
            softly.assertThat(received.saved() && closed.saved())
                .as("open-LOT receipt and LOT close cannot both commit: receipt=%s close=%s", received, closed).isFalse();
            softly.assertThat(jdbc.queryForObject("select count(*) from lot_master l join inventory i on i.lot_id=l.lot_id "
                + "where l.lot_id=? and l.lot_status='closed' and i.deleted_yn='N' and i.quantity>0", Integer.class, fixture.lot()))
                .as("A committed closed LOT cannot hold received stock").isZero();
        });
    }

    @Test
    void closingALotUsesCommittedStockInsteadOfPreviouslyCachedZeroQuantity() throws Exception {
        Fixture fixture = fixture(true);
        CountDownLatch cached = new CountDownLatch(1);
        CountDownLatch receiptCommitted = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Result closed;
        try {
            Future<Result> close = pool.submit(() -> asOwnerInTransaction(() -> {
                // getInventory loads the real JPA stock entity into this transaction's persistence context.
                assertThat(stocks.getInventory(fixture.inventory()).quantity()).isEqualByComparingTo(BigDecimal.ZERO);
                cached.countDown();
                await(receiptCommitted, "Wait for the other transaction's receipt to commit");
                lots.closeLot(fixture.lot());
            }));
            awaitStarted(cached, close, "The closing transaction did not cache its zero-quantity stock.");
            try {
                Result received = asOwnerInTransaction(() -> movements.createTransaction(receipt(fixture.inventory())));
                assertThat(received.saved()).as("The other transaction must commit its existing-row receipt: %s", received).isTrue();
                assertThat(quantity(fixture.inventory())).isEqualByComparingTo(BigDecimal.ONE);
            } finally {
                receiptCommitted.countDown();
            }
            closed = close.get(20, TimeUnit.SECONDS);
        } finally {
            receiptCommitted.countDown();
            shutdown(pool);
        }
        assertSoftly(softly -> {
            softly.assertThat(closed.error()).as("Committed on-hand stock must refuse a close from cached zero stock")
                .isEqualTo(ErrorCode.CONFLICT);
            softly.assertThat(quantity(fixture.inventory())).isEqualByComparingTo(BigDecimal.ONE);
            softly.assertThat(lotStatus(fixture.lot())).isEqualTo("available");
            softly.assertThat(jdbc.queryForObject("select count(*) from lot_status_event where lot_id=? and action='closed'",
                Integer.class, fixture.lot())).as("The refused close cannot append a close event").isZero();
        });
    }

    @Test
    void receivingWithStockAlreadyLockedRejectsAPendingCloseWithoutWaitingForItsLock() throws Exception {
        Fixture fixture = fixture(true);
        String requestId = UUID.randomUUID().toString();
        CountDownLatch lotLocked = new CountDownLatch(1);
        CountDownLatch releaseLot = new CountDownLatch(1);
        CountDownLatch stockLocked = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Result received;
        try {
            Future<Result> holder = pool.submit(() -> asOwnerInTransaction(() -> {
                lots.closeLot(fixture.lot());
                entities.flush();
                lotLocked.countDown();
                await(releaseLot, "Keep the real close uncommitted until the receipt has returned");
            }));
            awaitStarted(lotLocked, holder, "The other transaction did not acquire the LOT row lock.");
            Future<Result> receive = pool.submit(() -> asOwnerInTransaction(() -> {
                // Production, counts and FEFO can hold inventory locks before entering the movement service.
                assertThat(jdbc.queryForObject("select inventory_id from inventory where inventory_id=? for update",
                    String.class, fixture.inventory())).isEqualTo(fixture.inventory());
                stockLocked.countDown();
                movements.createTransaction(receipt(fixture.inventory(), requestId));
            }));
            awaitStarted(stockLocked, receive, "The receipt transaction did not acquire its preceding stock lock.");
            // This deadline is shorter than lock_timeout: waiting for that timeout is not a prompt business conflict.
            received = receive.get(5, TimeUnit.SECONDS);
            assertThat(releaseLot.getCount()).as("The competing LOT lock must still be held when the receipt returns").isEqualTo(1);
            assertSoftly(softly -> {
                softly.assertThat(received.error()).as("Stock-first receipt must refuse a busy LOT before creating a wait cycle")
                    .isEqualTo(ErrorCode.CONFLICT);
                softly.assertThat(quantity(fixture.inventory())).as("The refused receipt must roll back its quantity")
                    .isEqualByComparingTo(BigDecimal.ZERO);
                softly.assertThat(jdbc.queryForObject("select count(*) from inventory_transaction where project_id=? and request_id=?",
                    Integer.class, DEMO_PROJECT, requestId)).as("The refused receipt must roll back its ledger entry").isZero();
            });
            releaseLot.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS).saved()).isTrue();
        } finally {
            releaseLot.countDown();
            shutdown(pool);
        }
        assertThat(lotStatus(fixture.lot())).isEqualTo("closed");
    }

    @Test
    void closingAndReopeningInOneTransactionPreservesItsPendingStatusChanges() {
        Fixture fixture = fixture(false);
        Result changed = asOwnerInTransaction(() -> {
            assertThat(lots.closeLot(fixture.lot()).lotStatus()).isEqualTo("closed");
            assertThat(lots.reopenLot(fixture.lot()).lotStatus()).isEqualTo("available");
        });
        assertThat(changed.saved()).as("A later operation must see this transaction's own LOT edits").isTrue();
        assertThat(lotStatus(fixture.lot())).isEqualTo("available");
        assertThat(jdbc.queryForObject("select count(*) from lot_status_event where lot_id=?", Integer.class, fixture.lot()))
            .isEqualTo(2);
    }

    @Test
    void receivingAfterMarkingTheProducingRunKeepsTheUnflushedProductionOrigin() {
        Fixture fixture = fixture(true);
        String run = producingRun();
        Result changed = asOwnerInTransaction(() -> {
            lots.markProducedBy(fixture.lot(), run);
            movements.createTransaction(receipt(fixture.inventory()));
        });
        assertThat(changed.saved()).isTrue();
        assertThat(quantity(fixture.inventory())).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(jdbc.queryForObject("select production_run_id from lot_master where lot_id=?", String.class, fixture.lot()))
            .isEqualTo(run);
        assertThat(jdbc.queryForObject("select count(*) from lot_master where lot_id=? and produced_at is not null",
            Integer.class, fixture.lot())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void receivingAfterReopeningKeepsThisTransactionsReceiptCheckTransition(boolean releaseBeforeReceipt) {
        Fixture fixture = fixture(true);
        jdbc.update("update item set lot_release_required_yn='Y' where item_id=?", fixture.item());
        assertThat(asOwner(() -> lots.closeLot(fixture.lot())).saved()).isTrue();
        Result changed = asOwnerInTransaction(() -> {
            assertThat(lots.reopenLot(fixture.lot()).lotStatus()).isEqualTo("inspection_pending");
            if (releaseBeforeReceipt) {
                releases.release(fixture.lot());
            }
            movements.createTransaction(receipt(fixture.inventory()));
        });
        assertThat(changed.saved()).as("Reopen/release and the next receipt share one transaction").isTrue();
        assertThat(quantity(fixture.inventory())).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(lotStatus(fixture.lot())).isEqualTo(releaseBeforeReceipt ? "available" : "inspection_pending");
        assertThat(jdbc.queryForObject("select inventory_status from inventory where inventory_id=?", String.class, fixture.inventory()))
            .isEqualTo(releaseBeforeReceipt ? "available" : "quarantined");
    }

    @Test
    void receivingFromACachedLotKeepsAnotherTransactionsCommittedProductionOrigin() throws Exception {
        Fixture fixture = fixture(true);
        assertThat(asOwner(() -> stocks.deleteInventory(fixture.inventory())).saved()).isTrue();
        String run = producingRun();
        CountDownLatch cached = new CountDownLatch(1);
        CountDownLatch marked = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<String> receivedId = new AtomicReference<>();
        try {
            Future<Result> receive = pool.submit(() -> asOwnerInTransaction(() -> {
                assertThat(lots.getLot(fixture.lot()).productionRunId()).isNull();
                cached.countDown();
                await(marked, "Wait for the other transaction's production origin to commit");
                receivedId.set(stocks.createInventory(stockRequest(fixture, BigDecimal.ONE)).inventoryId());
            }));
            awaitStarted(cached, receive, "The receiving transaction did not cache its old LOT metadata.");
            try {
                assertThat(asOwner(() -> lots.markProducedBy(fixture.lot(), run)).saved()).isTrue();
                assertThat(jdbc.queryForObject("select production_run_id from lot_master where lot_id=?", String.class, fixture.lot()))
                    .isEqualTo(run);
            } finally {
                marked.countDown();
            }
            assertThat(receive.get(20, TimeUnit.SECONDS).saved()).isTrue();
        } finally {
            marked.countDown();
            shutdown(pool);
        }
        assertThat(quantity(receivedId.get())).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(jdbc.queryForObject("select production_run_id from lot_master where lot_id=?", String.class, fixture.lot()))
            .isEqualTo(run);
        assertThat(jdbc.queryForObject("select count(*) from lot_master where lot_id=? and produced_at is not null",
            Integer.class, fixture.lot())).isEqualTo(1);
    }

    private String producingRun() {
        String run = UUID.randomUUID().toString();
        jdbc.update("insert into production_run(production_run_id,project_id,run_number,run_type,run_status,planned_output_qty) "
            + "values(?,?,?,'actual','running',1)", run, DEMO_PROJECT, "LC-" + UUID.randomUUID().toString().substring(0, 24));
        return run;
    }

    private Fixture fixture(boolean withStock) {
        String item = UUID.randomUUID().toString();
        jdbc.update("insert into item(item_id,project_id,item_code,item_name,unit_id,item_status,lot_manage_yn,lot_release_required_yn) "
            + "values(?,?,?,'Close race','unit_kg','active','Y','N')", item, DEMO_PROJECT, "LC-" + item);
        AtomicReference<String> lot = new AtomicReference<>();
        assertThat(asOwner(() -> lot.set(lots.createLot(new LotCreateRequest(DEMO_PROJECT, item,
            "LC-" + item, null, null, null)).lotId())).saved()).isTrue();
        Fixture fixture = new Fixture(item, lot.get(), null);
        if (!withStock) {
            return fixture;
        }
        AtomicReference<String> inventory = new AtomicReference<>();
        assertThat(asOwner(() -> inventory.set(stocks.createInventory(stockRequest(fixture, BigDecimal.ZERO)).inventoryId())).saved())
            .isTrue();
        assertThat(lotStatus(fixture.lot())).isEqualTo("consumed");
        return new Fixture(item, lot.get(), inventory.get());
    }

    private InventoryAdjustRequest stockRequest(Fixture fixture, BigDecimal quantity) {
        return mapper.convertValue(Map.of("projectId", DEMO_PROJECT, "itemId", fixture.item(), "lotId", fixture.lot(),
            "quantity", quantity), InventoryAdjustRequest.class);
    }

    private InventoryTransactionCreateRequest receipt(String inventory) {
        return receipt(inventory, UUID.randomUUID().toString());
    }

    private InventoryTransactionCreateRequest receipt(String inventory, String requestId) {
        return new InventoryTransactionCreateRequest(inventory, "receipt", BigDecimal.ONE, null, requestId,
            "lot_close_regression", requestId, "Concurrent LOT close receipt");
    }

    private BigDecimal quantity(String inventory) {
        return jdbc.queryForObject("select quantity from inventory where inventory_id=?", BigDecimal.class, inventory);
    }

    private String lotStatus(String lot) {
        return jdbc.queryForObject("select lot_status from lot_master where lot_id=?", String.class, lot);
    }

    private Result asOwnerInTransaction(Runnable operation) {
        return asOwner(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("set local lock_timeout='15s'");
            jdbc.execute("set local statement_timeout='30s'");
            operation.run();
        }));
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

    private void awaitStarted(CountDownLatch latch, Future<?> worker, String message) throws Exception {
        if (!latch.await(20, TimeUnit.SECONDS)) {
            if (worker.isDone()) {
                worker.get(1, TimeUnit.SECONDS);
            }
            throw new AssertionError(message);
        }
    }

    private void await(CountDownLatch latch, String message) {
        try {
            assertThat(latch.await(45, TimeUnit.SECONDS)).as(message).isTrue();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, error);
        }
    }

    private void shutdown(ExecutorService pool) throws InterruptedException {
        pool.shutdownNow();
        assertThat(pool.awaitTermination(35, TimeUnit.SECONDS)).as("All race workers must finish before the next test").isTrue();
    }

    private record Fixture(String item, String lot, String inventory) {
    }

    private record Result(boolean saved, ErrorCode error) {
    }
}
