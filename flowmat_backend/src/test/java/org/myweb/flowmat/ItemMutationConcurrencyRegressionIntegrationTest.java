package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.myweb.flowmat.domain.catalog.api.dto.request.ItemImportRequest;
import org.myweb.flowmat.domain.catalog.api.dto.request.ItemUpdateRequest;
import org.myweb.flowmat.domain.catalog.application.ItemImportService;
import org.myweb.flowmat.domain.catalog.application.ItemService;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Pauses real reads, without replacing authorization decisions or persistence operations. */
@Timeout(60)
class ItemMutationConcurrencyRegressionIntegrationTest extends IntegrationTestSupport {

    @Autowired private ItemService items;
    @Autowired private ItemImportService imports;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtProvider jwt;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoSpyBean private ProjectAccessService access;
    @MockitoSpyBean private ItemRepository itemRepository;

    @Test
    void anOlderNameEditCannotResurrectAnItemThatWasDeleted() throws Exception {
        String id = item();
        Result older = overlapping(false, false, () -> items.updateItem(id, update(Map.of("itemName", "Renamed"))),
            () -> assertThat(asOwner(() -> items.deleteItem(id)).saved()).isTrue());
        assertThat(jdbc.queryForObject("select deleted_yn from item where item_id=?", String.class, id))
            .as("a committed deletion stays deleted").isEqualTo("Y");
        assertThat(older).isEqualTo(new Result(false, ErrorCode.NOT_FOUND));
    }

    @Test
    void anOlderNameEditKeepsTheCommittedUnitCostAndItsHistory() throws Exception {
        String id = item();
        Result older = overlapping(false, false, () -> items.updateItem(id, update(Map.of("itemName", "Renamed"))),
            () -> assertThat(asOwner(() -> items.updateItem(id, update(Map.of("unitCost", new BigDecimal("20"))))).saved()).isTrue());
        assertThat(older.saved()).isTrue();
        assertThat(cost(id)).as("a name-only edit cannot reverse a committed unit cost").isEqualByComparingTo("20");
        assertCostHistory(id);
        assertThat(jdbc.queryForObject("select item_name from item where item_id=?", String.class, id)).isEqualTo("Renamed");
    }

    @Test
    void anOlderNameEditKeepsTheCommittedBarcode() throws Exception {
        String id = item();
        String barcode = "BAR-" + UUID.randomUUID();
        Result older = overlapping(false, false, () -> items.updateItem(id, update(Map.of("itemName", "Renamed"))),
            () -> assertThat(asOwner(() -> items.updateItem(id, update(Map.of("details", Map.of("barcode", barcode))))).saved()).isTrue());
        assertThat(older.saved()).isTrue();
        assertThat(jdbc.queryForObject("select barcode from item where item_id=?", String.class, id))
            .as("an omitted details object preserves a concurrent details edit").isEqualTo(barcode);
    }

    @Test
    void anOlderDeleteKeepsTheCommittedUnitCostAndItsHistory() throws Exception {
        String id = item();
        Result older = overlapping(true, false, () -> items.deleteItem(id),
            () -> assertThat(asOwner(() -> items.updateItem(id, update(Map.of("unitCost", new BigDecimal("20"))))).saved()).isTrue());
        assertThat(older.saved()).isTrue();
        assertThat(cost(id)).as("deleting an item does not reverse its final unit cost").isEqualByComparingTo("20");
        assertCostHistory(id);
        assertThat(jdbc.queryForObject("select deleted_yn from item where item_id=?", String.class, id)).isEqualTo("Y");
    }

    @Test
    void anImportWithABlankBarcodeKeepsTheCommittedBarcode() throws Exception {
        String id = item();
        String barcode = "BAR-" + UUID.randomUUID();
        ItemImportRequest request = mapper.convertValue(Map.of("projectId", DEMO_PROJECT, "dryRun", false,
            "rows", List.of(Map.of("itemCode", id, "spec", "New specification"))), ItemImportRequest.class);
        Result older = overlapping(false, true, () -> assertThat(imports.importItems(request).applied()).isTrue(),
            () -> assertThat(asOwner(() -> items.updateItem(id, update(Map.of("details", Map.of("barcode", barcode))))).saved()).isTrue());
        assertThat(older.saved()).isTrue();
        assertThat(jdbc.queryForObject("select barcode from item where item_id=?", String.class, id))
            .as("a blank import cell keeps the current stored barcode").isEqualTo(barcode);
        assertThat(jdbc.queryForObject("select spec from item where item_id=?", String.class, id)).isEqualTo("New specification");
    }

    @Test
    void anImportMatchesTheCurrentCodeAfterAConcurrentRename() throws Exception {
        String id = item();
        ItemImportRequest request = importRequest(false, List.of(Map.of("itemCode", id, "itemName", "Imported", "spec", "Imported spec")));
        Result older = overlapping(false, true, () -> {
            var result = imports.importItems(request);
            assertThat(result.applied()).isTrue();
            assertThat(result.created()).isEqualTo(1);
            assertThat(result.updated()).isZero();
        }, () -> assertThat(asOwner(() -> items.updateItem(id, update(Map.of("itemCode", id + "-NEW")))).saved()).isTrue());
        assertThat(older.saved()).isTrue();
        assertThat(jdbc.queryForObject("select item_code from item where item_id=?", String.class, id)).isEqualTo(id + "-NEW");
        assertThat(jdbc.queryForObject("select spec from item where item_id=?", String.class, id)).isNull();
        assertThat(jdbc.queryForObject("select item_name from item where project_id=? and item_code=? and deleted_yn='N'",
            String.class, DEMO_PROJECT, id)).isEqualTo("Imported");
    }

    @Test
    void anImportDoesNotUpdateADeletedMatch() throws Exception {
        String id = item();
        ItemImportRequest request = importRequest(false, List.of(Map.of("itemCode", id, "itemName", "Replacement")));
        Result older = overlapping(false, true, () -> {
            var result = imports.importItems(request);
            assertThat(result.applied()).isTrue();
            assertThat(result.created()).isEqualTo(1);
        }, () -> assertThat(asOwner(() -> items.deleteItem(id)).saved()).isTrue());
        assertThat(older.saved()).isTrue();
        assertThat(jdbc.queryForObject("select deleted_yn from item where item_id=?", String.class, id)).isEqualTo("Y");
        String replacement = jdbc.queryForObject("select item_id from item where project_id=? and item_code=? and deleted_yn='N'",
            String.class, DEMO_PROJECT, id);
        assertThat(replacement).isNotEqualTo(id);
    }

    @Test
    void aDryRunDoesNotWaitForAnItemWriteLockOrChangeIt() throws Exception {
        String id = item();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("select item_id from item where item_id=? for update", String.class, id);
                locked.countDown();
                try {
                    assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var preview = pool.submit(() -> asOwner(() -> {
                var response = imports.importItems(importRequest(true, List.of(Map.of("itemCode", id, "spec", "Preview only"))));
                assertThat(response.dryRun()).isTrue();
                assertThat(response.applied()).isFalse();
                assertThat(response.updated()).isEqualTo(1);
            }));
            try {
                assertThat(preview.get(8, TimeUnit.SECONDS).saved()).isTrue();
                assertThat(jdbc.queryForObject("select spec from item where item_id=?", String.class, id)).isNull();
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void reverseOrderImportsPreserveBothSetsOfDetails() throws Exception {
        String first = item();
        String second = item();
        var readTogether = new CyclicBarrier(2);
        var delegated = mockingDetails(itemRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(call -> {
            Object result = delegated.answer(call);
            readTogether.await(10, TimeUnit.SECONDS);
            return result;
        }).when(itemRepository).findAllByProjectIdAndDeletedYnOrderByCreatedAtAsc(DEMO_PROJECT, "N");
        ItemImportRequest barcodes = importRequest(false, List.of(
            Map.of("itemCode", first, "barcode", "BAR-" + first), Map.of("itemCode", second, "barcode", "BAR-" + second)));
        ItemImportRequest specs = importRequest(false, List.of(
            Map.of("itemCode", second, "spec", "Spec-" + second), Map.of("itemCode", first, "spec", "Spec-" + first)));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> asOwner(() -> assertThat(imports.importItems(barcodes).applied()).isTrue()));
            var two = pool.submit(() -> asOwner(() -> assertThat(imports.importItems(specs).applied()).isTrue()));
            assertThat(one.get(20, TimeUnit.SECONDS).saved()).isTrue();
            assertThat(two.get(20, TimeUnit.SECONDS).saved()).isTrue();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        for (String id : List.of(first, second)) {
            assertThat(jdbc.queryForObject("select barcode from item where item_id=?", String.class, id)).isEqualTo("BAR-" + id);
            assertThat(jdbc.queryForObject("select spec from item where item_id=?", String.class, id)).isEqualTo("Spec-" + id);
        }
    }

    private ItemImportRequest importRequest(boolean dryRun, List<Map<String, String>> rows) {
        return mapper.convertValue(Map.of("projectId", DEMO_PROJECT, "dryRun", dryRun, "rows", rows), ItemImportRequest.class);
    }

    private Result overlapping(boolean ownerOnly, boolean importing, Runnable older, Runnable newer) throws Exception {
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<Thread> pausedThread = new AtomicReference<>();
        if (importing) {
            var delegated = mockingDetails(itemRepository).getMockCreationSettings().getDefaultAnswer();
            doAnswer(call -> {
                Object result = delegated.answer(call);
                pause(pausedThread, read, resume);
                return result;
            }).when(itemRepository).findAllByProjectIdAndDeletedYnOrderByCreatedAtAsc(DEMO_PROJECT, "N");
        } else if (ownerOnly) {
            doAnswer(call -> {
                Object result = call.callRealMethod();
                pause(pausedThread, read, resume);
                return result;
            }).when(access).requireProjectOwnerAccess(DEMO_PROJECT);
        } else {
            doAnswer(call -> {
                Object result = call.callRealMethod();
                pause(pausedThread, read, resume);
                return result;
            }).when(access).requireProjectWriteAccess(DEMO_PROJECT);
        }
        var pool = Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> {
                pausedThread.set(Thread.currentThread());
                return asOwner(older);
            });
            if (!read.await(20, TimeUnit.SECONDS)) {
                if (pending.isDone()) pending.get(1, TimeUnit.SECONDS);
                throw new AssertionError("The older request did not finish its real read.");
            }
            try {
                newer.run();
            } finally {
                resume.countDown();
            }
            return pending.get(20, TimeUnit.SECONDS);
        } finally {
            resume.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void pause(AtomicReference<Thread> paused, CountDownLatch read, CountDownLatch resume) throws Exception {
        if (Thread.currentThread() == paused.get()) {
            read.countDown();
            assertThat(resume.await(20, TimeUnit.SECONDS)).as("the older request was released").isTrue();
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
        String id = "MUT-" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("insert into item(item_id,project_id,item_code,item_name,item_status,unit_cost,deleted_yn) "
            + "values(?,?,?,'Original','active',10,'N')", id, DEMO_PROJECT, id);
        return id;
    }

    private ItemUpdateRequest update(Map<String, Object> fields) {
        return mapper.convertValue(fields, ItemUpdateRequest.class);
    }

    private BigDecimal cost(String id) {
        return jdbc.queryForObject("select unit_cost from item where item_id=?", BigDecimal.class, id);
    }

    private void assertCostHistory(String id) {
        List<BigDecimal> history = jdbc.queryForList("select unit_cost from item_cost_history where item_id=?", BigDecimal.class, id);
        assertThat(history).hasSize(1);
        assertThat(history.get(0)).isEqualByComparingTo("20");
    }

    private record Result(boolean saved, ErrorCode error) {
    }
}
