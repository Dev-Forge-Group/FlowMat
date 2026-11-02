package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.math.BigDecimal;
import java.util.List;
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
import org.myweb.flowmat.domain.inventory.api.dto.response.InventoryTransactionResponse;
import org.myweb.flowmat.domain.inventory.application.InventoryService;
import org.myweb.flowmat.domain.inventory.application.InventoryTransactionService;
import org.myweb.flowmat.domain.inventory.application.LotService;
import org.myweb.flowmat.domain.quality.api.dto.response.LotReleaseResponse;
import org.myweb.flowmat.domain.quality.application.LotReleaseService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Commands must use the stock state committed before the command, even when their caller read that stock earlier in
 * the same transaction. All lifecycle and stock changes use public authenticated services. IntegrationTestSupport
 * supplies disposable PostgreSQL/Redis; SQL only inserts a new item fixture and reads committed results.
 */
@Timeout(120)
class LotCachedCommandStateRegressionIntegrationTest extends IntegrationTestSupport {
    private static final BigDecimal FIVE = BigDecimal.valueOf(5);

    @Autowired private InventoryService stocks;
    @Autowired private InventoryTransactionService movements;
    @Autowired private LotService lots;
    @Autowired private LotReleaseService releases;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtProvider jwt;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void releasingAReopenedLotUsesItsCurrentHeldRowsAndCommittedQuantity() throws InterruptedException {
        Fixture fixture = fixture(true, true);
        LotReleaseResponse initialRelease = asOwner(() -> releases.release(fixture.lot()));
        assertThat(initialRelease.lotStatus()).isEqualTo("available");
        assertThat(initialRelease.checkedStandards()).isZero();
        List<String> earlierReleaseIds = inspectionReleases(fixture).stream()
            .map(InventoryTransactionResponse::inventoryTransactionId).toList();
        assertThat(earlierReleaseIds).as("Preparing available stock performs one real initial release").hasSize(1);

        ExecutorService writer = Executors.newSingleThreadExecutor();
        Outcome<LotReleaseResponse> released;
        try {
            released = outcome(() -> asOwner(() -> inNewTransaction(() -> {
                requireEarlierAvailableStock(fixture);
                Future<String> reopened = writer.submit(() -> asOwner(() -> inNewTransaction(() -> {
                    movements.createTransaction(movement(fixture, "issue", FIVE, UUID.randomUUID().toString()));
                    assertThat(lots.closeLot(fixture.lot()).lotStatus()).isEqualTo("closed");
                    return lots.reopenLot(fixture.lot()).lotStatus();
                })));
                assertThat(awaitCommit(reopened)).as("The other transaction committed reopening for receipt checks")
                    .isEqualTo("inspection_pending");
                assertThat(committedQuantity(fixture.lot())).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(committedStockStatus(fixture.inventory())).isEqualTo("quarantined");
                return releases.release(fixture.lot());
            })));
        } finally {
            shutdown(writer);
        }

        var freshStock = asOwner(() -> stocks.getInventory(fixture.inventory()));
        var freshLot = asOwner(() -> lots.getLot(fixture.lot()));
        var addedReleases = inspectionReleases(fixture).stream()
            .filter(row -> !earlierReleaseIds.contains(row.inventoryTransactionId())).toList();
        assertSoftly(softly -> {
            softly.assertThat(released.error()).as("Release LOT must succeed against the current pending LOT").isNull();
            if (released.value() != null) {
                softly.assertThat(released.value().lotStatus()).as("An existing empty stock row releases as consumed")
                    .isEqualTo("consumed");
            }
            softly.assertThat(freshStock.inventoryStatus()).as("The release must actually unquarantine the row")
                .isEqualTo("available");
            softly.assertThat(freshStock.quantity()).isEqualByComparingTo(BigDecimal.ZERO);
            softly.assertThat(freshStock.reservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
            softly.assertThat(freshLot.quantityOnHand()).isEqualByComparingTo(BigDecimal.ZERO);
            softly.assertThat(freshLot.lotStatus()).as("The committed LOT status must follow committed zero stock")
                .isEqualTo("consumed");
            softly.assertThat(committedQuantity(fixture.lot())).isEqualByComparingTo(BigDecimal.ZERO);
            softly.assertThat(addedReleases).as("R3 records one new release after this reopening").hasSize(1);
            for (var release : addedReleases) {
                softly.assertThat(release.transactionType()).isEqualTo("unquarantine");
                softly.assertThat(release.inventoryId()).isEqualTo(fixture.inventory());
                softly.assertThat(release.lotId()).isEqualTo(fixture.lot());
                softly.assertThat(release.quantityDelta()).isEqualByComparingTo(BigDecimal.ZERO);
                softly.assertThat(release.reservedDelta()).isEqualByComparingTo(BigDecimal.ZERO);
                softly.assertThat(release.quantityAfter()).isEqualByComparingTo(BigDecimal.ZERO);
                softly.assertThat(release.createdBy()).isEqualTo(DEMO_OWNER);
            }
        });
    }

    @ParameterizedTest(name = "LOT tracked = {0}")
    @ValueSource(booleans = {true, false})
    void quarantiningStockAlreadyQuarantinedByAnotherTransactionIsRefusedWithoutAnotherLedgerRow(boolean tracked)
        throws InterruptedException {
        Fixture fixture = fixture(tracked, false);
        String committedRequest = UUID.randomUUID().toString();
        String refusedRequest = UUID.randomUUID().toString();
        ExecutorService writer = Executors.newSingleThreadExecutor();
        Outcome<InventoryTransactionResponse> attempted;
        try {
            attempted = outcome(() -> afterCommittedQuarantine(fixture, writer, committedRequest,
                () -> movements.createTransaction(movement(fixture, "quarantine", null, refusedRequest))));
        } finally {
            shutdown(writer);
        }

        var freshStock = asOwner(() -> stocks.getInventory(fixture.inventory()));
        var freshLot = fixture.lot() == null ? null : asOwner(() -> lots.getLot(fixture.lot()));
        var ledger = ledger(fixture);
        assertSoftly(softly -> {
            softly.assertThat(attempted.error()).as("A different request cannot quarantine already quarantined stock")
                .isEqualTo(ErrorCode.CONFLICT);
            softly.assertThat(attempted.value()).isNull();
            softly.assertThat(freshStock.inventoryStatus()).isEqualTo("quarantined");
            softly.assertThat(freshStock.quantity()).isEqualByComparingTo(FIVE);
            softly.assertThat(freshStock.lotId()).isEqualTo(fixture.lot());
            softly.assertThat(freshStock.reservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
            softly.assertThat(freshStock.availableQuantity()).isEqualByComparingTo(FIVE);
            softly.assertThat(ledger).hasSize(2);
            if (freshLot != null) {
                softly.assertThat(freshLot.lotStatus()).isEqualTo("quarantined");
                softly.assertThat(freshLot.quantityOnHand()).isEqualByComparingTo(FIVE);
            }
            softly.assertThat(ledger.stream().filter(row -> committedRequest.equals(row.requestId())).toList())
                .as("The other transaction's quarantine remains committed").hasSize(1);
            softly.assertThat(ledger.stream().filter(row -> refusedRequest.equals(row.requestId())).toList())
                .as("The refused command cannot append a duplicate quarantine movement").isEmpty();
        });
    }

    @ParameterizedTest(name = "LOT tracked = {0}")
    @ValueSource(booleans = {true, false})
    void unquarantiningStockQuarantinedByAnotherTransactionUsesItsCurrentState(boolean tracked) throws InterruptedException {
        Fixture fixture = fixture(tracked, false);
        String committedRequest = UUID.randomUUID().toString();
        String releaseRequest = UUID.randomUUID().toString();
        ExecutorService writer = Executors.newSingleThreadExecutor();
        Outcome<InventoryTransactionResponse> released;
        try {
            released = outcome(() -> afterCommittedQuarantine(fixture, writer, committedRequest,
                () -> movements.createTransaction(movement(fixture, "unquarantine", null, releaseRequest))));
        } finally {
            shutdown(writer);
        }

        var freshStock = asOwner(() -> stocks.getInventory(fixture.inventory()));
        var freshLot = fixture.lot() == null ? null : asOwner(() -> lots.getLot(fixture.lot()));
        var ledger = ledger(fixture);
        var addedReleases = ledger.stream().filter(row -> releaseRequest.equals(row.requestId())).toList();
        assertSoftly(softly -> {
            softly.assertThat(released.error()).as("Currently quarantined stock must be releasable").isNull();
            if (released.value() != null) {
                softly.assertThat(released.value().transactionType()).isEqualTo("unquarantine");
                softly.assertThat(released.value().requestId()).isEqualTo(releaseRequest);
            }
            softly.assertThat(freshStock.inventoryStatus()).isEqualTo("available");
            softly.assertThat(freshStock.quantity()).isEqualByComparingTo(FIVE);
            softly.assertThat(freshStock.lotId()).isEqualTo(fixture.lot());
            softly.assertThat(freshStock.reservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
            softly.assertThat(freshStock.availableQuantity()).isEqualByComparingTo(FIVE);
            softly.assertThat(ledger).hasSize(3);
            if (freshLot != null) {
                softly.assertThat(freshLot.lotStatus()).isEqualTo("available");
                softly.assertThat(freshLot.quantityOnHand()).isEqualByComparingTo(FIVE);
            }
            softly.assertThat(ledger.stream().filter(row -> committedRequest.equals(row.requestId())).toList())
                .as("The competing quarantine stays in the ledger").hasSize(1);
            softly.assertThat(addedReleases).as("The successful unquarantine is recorded once").hasSize(1);
            for (var release : addedReleases) {
                softly.assertThat(release.transactionType()).isEqualTo("unquarantine");
                softly.assertThat(release.inventoryId()).isEqualTo(fixture.inventory());
                softly.assertThat(release.lotId()).isEqualTo(fixture.lot());
                softly.assertThat(release.quantityDelta()).isEqualByComparingTo(BigDecimal.ZERO);
                softly.assertThat(release.reservedDelta()).isEqualByComparingTo(BigDecimal.ZERO);
                softly.assertThat(release.quantityAfter()).isEqualByComparingTo(FIVE);
                softly.assertThat(release.createdBy()).isEqualTo(DEMO_OWNER);
            }
        });
    }

    private <T> T afterCommittedQuarantine(Fixture fixture, ExecutorService writer, String requestId,
        Supplier<T> nextCommand) {
        return asOwner(() -> inNewTransaction(() -> {
            requireEarlierAvailableStock(fixture);
            Future<InventoryTransactionResponse> quarantined = writer.submit(() -> asOwner(() -> inNewTransaction(
                () -> movements.createTransaction(movement(fixture, "quarantine", null, requestId)))));
            assertThat(awaitCommit(quarantined).transactionType()).isEqualTo("quarantine");
            assertThat(committedStockStatus(fixture.inventory())).as("The other quarantine committed before this command")
                .isEqualTo("quarantined");
            return nextCommand.get();
        }));
    }

    private void requireEarlierAvailableStock(Fixture fixture) {
        var earlier = stocks.getInventory(fixture.inventory());
        assertThat(earlier.inventoryStatus()).isEqualTo("available");
        assertThat(earlier.quantity()).isEqualByComparingTo(FIVE);
    }

    private Fixture fixture(boolean tracked, boolean receiptChecksRequired) {
        String item = UUID.randomUUID().toString();
        jdbc.update("insert into item(item_id,project_id,item_code,item_name,unit_id,item_status,lot_manage_yn,lot_release_required_yn) "
            + "values(?,?,?,'Cached LOT command','unit_kg','active',?,?)", item, DEMO_PROJECT, "LCC-" + item,
            tracked ? "Y" : "N",
            receiptChecksRequired ? "Y" : "N");
        return asOwner(() -> {
            String lot = tracked
                ? lots.createLot(new LotCreateRequest(DEMO_PROJECT, item, "LCC-" + item, null, null, null)).lotId()
                : null;
            String inventory = stocks.createInventory(new InventoryAdjustRequest(DEMO_PROJECT, item, FIVE,
                null, null, null, null, null, null, null, lot)).inventoryId();
            return new Fixture(lot, inventory);
        });
    }

    private InventoryTransactionCreateRequest movement(Fixture fixture, String type, BigDecimal quantity, String requestId) {
        return new InventoryTransactionCreateRequest(fixture.inventory(), type, quantity, null, requestId,
            "lot_cached_command_regression", requestId, "Command follows the committed stock state");
    }

    private List<InventoryTransactionResponse> ledger(Fixture fixture) {
        return asOwner(() -> movements.listTransactions(DEMO_PROJECT, fixture.inventory()));
    }

    private List<InventoryTransactionResponse> inspectionReleases(Fixture fixture) {
        return ledger(fixture).stream().filter(row -> "lot_inspection_release".equals(row.referenceType())
            && fixture.lot().equals(row.referenceId())).toList();
    }

    private BigDecimal committedQuantity(String lot) {
        return jdbc.queryForObject("select coalesce(sum(quantity),0) from inventory where lot_id=? and deleted_yn='N'",
            BigDecimal.class, lot);
    }

    private String committedStockStatus(String inventory) {
        return jdbc.queryForObject("select inventory_status from inventory where inventory_id=?", String.class, inventory);
    }

    private <T> Outcome<T> outcome(Supplier<T> command) {
        try {
            return new Outcome<>(command.get(), null);
        } catch (BusinessException failure) {
            return new Outcome<>(null, failure.getErrorCode());
        }
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
            throw new AssertionError("Interrupted before the competing transaction committed", interrupted);
        } catch (ExecutionException failed) {
            throw new AssertionError("The competing transaction failed", failed.getCause());
        } catch (TimeoutException timedOut) {
            throw new AssertionError("The competing transaction did not commit in time", timedOut);
        }
    }

    private void shutdown(ExecutorService writer) throws InterruptedException {
        writer.shutdownNow();
        assertThat(writer.awaitTermination(35, TimeUnit.SECONDS)).as("All competing transactions must finish").isTrue();
    }

    private record Fixture(String lot, String inventory) {
    }

    private record Outcome<T>(T value, ErrorCode error) {
    }
}
