package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryAdjustRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryTransactionCreateRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.LotCreateRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.LotResponse;
import org.myweb.flowmat.domain.inventory.application.InventoryService;
import org.myweb.flowmat.domain.inventory.application.InventoryTransactionService;
import org.myweb.flowmat.domain.inventory.application.LotReleaseHistoryService;
import org.myweb.flowmat.domain.inventory.application.LotService;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Public-service regressions for lot-release.md R6: reopening follows current quantity and quarantine state. Without
 * receipt checks, an existing empty row reopens as consumed; with checks, every row must be held again.
 * IntegrationTestSupport supplies disposable
 * Postgres/Redis; this test has no development-database endpoint or repository/entity-manager access.
 */
@Timeout(120)
class LotReopenCachedStockRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private InventoryService stocks;
    @Autowired private InventoryTransactionService movements;
    @Autowired private LotService lots;
    @Autowired private LotReleaseHistoryService releaseHistory;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtProvider jwt;
    @Autowired private PlatformTransactionManager transactions;

    @ParameterizedTest(name = "reopen follows committed zero stock after preloading {0}")
    @ValueSource(ints = {0, 5})
    void reopeningUsesCommittedStockInsteadOfTheQuantityPreviouslyReadInThisTransaction(int initialQuantity)
        throws InterruptedException {
        Fixture fixture = fixture(BigDecimal.valueOf(initialQuantity));
        ExecutorService closer = Executors.newSingleThreadExecutor();
        LotResponse reopened;
        try {
            reopened = asOwner(() -> inNewTransaction(() -> {
                // Keep this public read and the later reopen in one transaction. The competing writer must commit
                // between them; no assertion below requires a particular cache, lock or query implementation.
                assertThat(stocks.getInventory(fixture.inventory()).quantity())
                    .isEqualByComparingTo(BigDecimal.valueOf(initialQuantity));

                Future<LotResponse> closing = closer.submit(() -> asOwner(() -> inNewTransaction(() -> {
                    if (initialQuantity > 0) {
                        String requestId = UUID.randomUUID().toString();
                        movements.createTransaction(new InventoryTransactionCreateRequest(fixture.inventory(), "issue",
                            BigDecimal.valueOf(initialQuantity), null, requestId, "lot_reopen_regression", requestId,
                            "Empty the stock before closing its LOT"));
                    }
                    return lots.closeLot(fixture.lot());
                })));
                assertThat(awaitCommit(closing).lotStatus()).as("The other transaction has committed a real close")
                    .isEqualTo("closed");
                assertThat(committedQuantity(fixture.lot())).as("Committed stock is zero before reopening")
                    .isEqualByComparingTo(BigDecimal.ZERO);

                return lots.reopenLot(fixture.lot());
            }));
        } finally {
            closer.shutdownNow();
            assertThat(closer.awaitTermination(35, TimeUnit.SECONDS)).as("The competing transaction must finish")
                .isTrue();
        }

        // Check after the reopening transaction commits, so a stale response cannot hide a stale persisted status.
        LotResponse fresh = asOwner(() -> lots.getLot(fixture.lot()));
        assertSoftly(softly -> {
            softly.assertThat(reopened.quantityOnHand()).as("Reopen response must report the committed on-hand quantity")
                .isEqualByComparingTo(BigDecimal.ZERO);
            softly.assertThat(reopened.quantityReserved()).as("Reopen response must report the committed reservation")
                .isEqualByComparingTo(BigDecimal.ZERO);
            softly.assertThat(reopened.lotStatus()).as("R6: an existing, empty, unquarantined stock row reopens as consumed")
                .isEqualTo("consumed");
            softly.assertThat(fresh.quantityOnHand()).as("A new public read must still report zero stock")
                .isEqualByComparingTo(BigDecimal.ZERO);
            softly.assertThat(fresh.lotStatus()).as("The committed LOT status must follow its committed stock")
                .isEqualTo("consumed");
            softly.assertThat(committedQuantity(fixture.lot())).as("Reopening cannot restore previously issued stock")
                .isEqualByComparingTo(BigDecimal.ZERO);
            softly.assertThat(jdbc.queryForObject("select lot_status from lot_master where lot_id=?", String.class,
                fixture.lot())).as("The database must persist consumed, not the quantity from the earlier read")
                .isEqualTo("consumed");
        });
    }

    @Test
    void reopeningForReceiptChecksHoldsARecordWhoseEarlierReadWasQuarantined() throws InterruptedException {
        Fixture fixture = fixture(BigDecimal.valueOf(5), true);
        assertThat(asOwner(() -> lots.getLot(fixture.lot())).lotStatus()).isEqualTo("inspection_pending");
        // R5 turns the initial inspection wait into ordinary quarantine, which may later be unquarantined (R4).
        asOwner(() -> movements.createTransaction(movement(fixture.inventory(), "quarantine", null)));
        assertThat(asOwner(() -> lots.getLot(fixture.lot())).lotStatus()).isEqualTo("quarantined");

        ExecutorService closer = Executors.newSingleThreadExecutor();
        LotResponse reopened;
        try {
            reopened = asOwner(() -> inNewTransaction(() -> {
                var earlier = stocks.getInventory(fixture.inventory());
                assertThat(earlier.quantity()).isEqualByComparingTo(BigDecimal.valueOf(5));
                assertThat(earlier.inventoryStatus()).isEqualTo("quarantined");

                Future<LotResponse> closing = closer.submit(() -> asOwner(() -> inNewTransaction(() -> {
                    movements.createTransaction(movement(fixture.inventory(), "unquarantine", null));
                    movements.createTransaction(movement(fixture.inventory(), "issue", BigDecimal.valueOf(5)));
                    return lots.closeLot(fixture.lot());
                })));
                assertThat(awaitCommit(closing).lotStatus()).isEqualTo("closed");
                assertThat(committedQuantity(fixture.lot())).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(jdbc.queryForObject("select inventory_status from inventory where inventory_id=?", String.class,
                    fixture.inventory())).as("The other transaction committed an unquarantined row before reopen")
                    .isEqualTo("available");

                return lots.reopenLot(fixture.lot());
            }));
        } finally {
            closer.shutdownNow();
            assertThat(closer.awaitTermination(35, TimeUnit.SECONDS)).as("The competing transaction must finish")
                .isTrue();
        }

        LotResponse freshLot = asOwner(() -> lots.getLot(fixture.lot()));
        var freshStock = asOwner(() -> stocks.getInventory(fixture.inventory()));
        var holds = asOwner(() -> movements.listTransactions(DEMO_PROJECT, fixture.inventory())).stream()
            .filter(row -> "lot_reopen".equals(row.referenceType()) && fixture.lot().equals(row.referenceId()))
            .toList();
        var history = asOwner(() -> releaseHistory.history(fixture.lot())).events();
        var reopenings = history.stream().filter(event -> "reopened".equals(event.action())).toList();
        var heldEvents = history.stream().filter(event -> "held".equals(event.action())).toList();
        assertSoftly(softly -> {
            softly.assertThat(reopened.quantityOnHand()).as("Reopen must report the committed quantity")
                .isEqualByComparingTo(BigDecimal.ZERO);
            softly.assertThat(reopened.lotStatus()).as("R6: this item requires receipt checks after reopening")
                .isEqualTo("inspection_pending");
            softly.assertThat(freshLot.lotStatus()).isEqualTo("inspection_pending");
            softly.assertThat(freshStock.quantity()).isEqualByComparingTo(BigDecimal.ZERO);
            softly.assertThat(freshStock.inventoryStatus()).as("The row must be held again based on its current state")
                .isEqualTo("quarantined");

            softly.assertThat(holds).as("R6 records one actual hold for this previously unquarantined row").hasSize(1);
            for (var hold : holds) {
                softly.assertThat(hold.transactionType()).isEqualTo("quarantine");
                softly.assertThat(hold.inventoryId()).isEqualTo(fixture.inventory());
                softly.assertThat(hold.lotId()).isEqualTo(fixture.lot());
                softly.assertThat(hold.quantityDelta()).isEqualByComparingTo(BigDecimal.ZERO);
                softly.assertThat(hold.reservedDelta()).isEqualByComparingTo(BigDecimal.ZERO);
                softly.assertThat(hold.quantityAfter()).isEqualByComparingTo(BigDecimal.ZERO);
                softly.assertThat(hold.createdBy()).isEqualTo(DEMO_OWNER);
            }

            softly.assertThat(reopenings).as("R9 retains one successful reopening event").hasSize(1);
            for (var reopening : reopenings) {
                softly.assertThat(reopening.status()).isEqualTo("inspection_pending");
                softly.assertThat(reopening.by()).isEqualTo(DEMO_OWNER);
                softly.assertThat(reopening.stockRows()).isZero();
            }
            softly.assertThat(heldEvents).as("Release history must expose the new hold").hasSize(1);
            for (var held : heldEvents) {
                softly.assertThat(held.stockRows()).isEqualTo(1);
                softly.assertThat(held.by()).isEqualTo(DEMO_OWNER);
            }
            if (reopenings.size() == 1 && heldEvents.size() == 1) {
                softly.assertThat(reopenings.get(0).at()).as("R9: reopening precedes the hold it causes")
                    .isBeforeOrEqualTo(heldEvents.get(0).at());
                softly.assertThat(history.indexOf(heldEvents.get(0))).as("History is newest first")
                    .isLessThan(history.indexOf(reopenings.get(0)));
            }
        });
    }

    private Fixture fixture(BigDecimal initialQuantity) {
        return fixture(initialQuantity, false);
    }

    private Fixture fixture(BigDecimal initialQuantity, boolean receiptChecksRequired) {
        String item = UUID.randomUUID().toString();
        jdbc.update("insert into item(item_id,project_id,item_code,item_name,unit_id,item_status,lot_manage_yn,lot_release_required_yn) "
            + "values(?,?,?,'Reopen cached stock','unit_kg','active','Y',?)", item, DEMO_PROJECT, "LRC-" + item,
            receiptChecksRequired ? "Y" : "N");
        return asOwner(() -> {
            String lot = lots.createLot(new LotCreateRequest(DEMO_PROJECT, item, "LRC-" + item, null, null, null)).lotId();
            String inventory = stocks.createInventory(new InventoryAdjustRequest(DEMO_PROJECT, item, initialQuantity,
                null, null, null, null, null, null, null, lot)).inventoryId();
            return new Fixture(lot, inventory);
        });
    }

    private InventoryTransactionCreateRequest movement(String inventory, String type, BigDecimal quantity) {
        String requestId = UUID.randomUUID().toString();
        return new InventoryTransactionCreateRequest(inventory, type, quantity, null, requestId,
            "lot_reopen_regression", requestId, "Reopen follows committed stock state");
    }

    private BigDecimal committedQuantity(String lot) {
        return jdbc.queryForObject("select coalesce(sum(quantity),0) from inventory where lot_id=? and deleted_yn='N'",
            BigDecimal.class, lot);
    }

    private <T> T asOwner(Supplier<T> action) {
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(jwt.getAuthentication(jwt.generateAccessToken(DEMO_OWNER)));
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private <T> T inNewTransaction(Supplier<T> action) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template.execute(status -> {
            jdbc.execute("set local lock_timeout='15s'");
            jdbc.execute("set local statement_timeout='30s'");
            return action.get();
        });
    }

    private <T> T awaitCommit(Future<T> competing) {
        try {
            return competing.get(20, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted before the other transaction committed", interrupted);
        } catch (ExecutionException failed) {
            throw new AssertionError("The competing issue/close transaction failed", failed.getCause());
        } catch (TimeoutException timedOut) {
            throw new AssertionError("The competing issue/close transaction did not commit in time", timedOut);
        }
    }

    private record Fixture(String lot, String inventory) {
    }
}
