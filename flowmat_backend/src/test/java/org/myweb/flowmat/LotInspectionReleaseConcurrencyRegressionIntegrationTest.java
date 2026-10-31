package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryAdjustRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.LotCreateRequest;
import org.myweb.flowmat.domain.inventory.application.InventoryService;
import org.myweb.flowmat.domain.inventory.application.LotService;
import org.myweb.flowmat.domain.inventory.application.LotReleaseCommandImpl;
import org.myweb.flowmat.domain.quality.application.LotReleaseService;
import org.myweb.flowmat.domain.quality.application.QualityService;
import org.myweb.flowmat.domain.quality.api.dto.request.QualityInspectionCreateRequest;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real services and commit boundaries, using only IntegrationTestSupport's disposable databases. */
@Timeout(120)
class LotInspectionReleaseConcurrencyRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private LotService lots;
    @Autowired private InventoryService stocks;
    @Autowired private LotReleaseService releases;
    @Autowired private QualityService quality;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoSpyBean private LotReleaseCommandImpl releaseCommand;

    @Test
    void aConcurrentFailedInspectionCannotBeReleasedFromCachedPendingStatus() throws Exception {
        String item = UUID.randomUUID().toString();
        jdbc.update("insert into item(item_id,project_id,item_code,item_name,unit_id,item_status,lot_manage_yn,lot_release_required_yn) "
            + "values(?,?,?,'Release race','unit_kg','active','Y','Y')", item, DEMO_PROJECT, "LR-" + item);
        AtomicReference<String> lot = new AtomicReference<>();
        assertThat(asOwner(() -> lot.set(lots.createLot(new LotCreateRequest(DEMO_PROJECT, item, "LR-" + item,
            null, null, null)).lotId())).saved()).isTrue();
        AtomicReference<String> stock = new AtomicReference<>();
        assertThat(asOwner(() -> stock.set(stocks.createInventory(mapper.convertValue(Map.of("projectId", DEMO_PROJECT,
            "itemId", item, "lotId", lot.get(), "quantity", 1), InventoryAdjustRequest.class)).inventoryId())).saved()).isTrue();
        String standard = UUID.randomUUID().toString();
        jdbc.update("insert into inspection_standard(standard_id,project_id,item_id,inspection_type,stage,standard_min,standard_max,unit,required_yn,active_yn) "
            + "values(?,?,?,'Visual','receipt',0,10,'kg','Y','Y')", standard, DEMO_PROJECT, item);
        assertThat(asOwner(() -> quality.recordInspection(inspection(lot.get(), standard, false))).saved()).isTrue();
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<Thread> releaser = new AtomicReference<>();
        var delegated = mockingDetails(releaseCommand).getMockCreationSettings().getDefaultAnswer();
        doAnswer(call -> {
            if (Thread.currentThread() == releaser.get()) {
                // LotReleaseService has read the real pending LOT and latest required PASS before this call.
                checked.countDown();
                assertThat(resume.await(40, TimeUnit.SECONDS)).isTrue();
            }
            return delegated.answer(call);
        }).when(releaseCommand).releaseAfterReceiptChecks(lot.get(), DEMO_OWNER);
        var pool = Executors.newSingleThreadExecutor();
        Result result;
        try {
            var pending = pool.submit(() -> {
                releaser.set(Thread.currentThread());
                return asOwner(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    jdbc.execute("set local lock_timeout='15s'");
                    jdbc.execute("set local statement_timeout='30s'");
                    releases.release(lot.get());
                }));
            });
            if (!checked.await(20, TimeUnit.SECONDS)) {
                if (pending.isDone()) pending.get(1, TimeUnit.SECONDS);
                throw new AssertionError("The release did not finish its real receipt-check validation.");
            }
            try {
                assertThat(asOwner(() -> quality.recordInspection(inspection(lot.get(), standard, true))).saved()).isTrue();
                assertThat(jdbc.queryForObject("select lot_status from lot_master where lot_id=?", String.class, lot.get()))
                    .isEqualTo("quarantined");
            } finally {
                resume.countDown();
            }
            result = pending.get(20, TimeUnit.SECONDS);
        } finally {
            resume.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(35, TimeUnit.SECONDS)).isTrue();
        }
        assertSoftly(softly -> {
            softly.assertThat(result.error()).as("a committed failed inspection must refuse the stale release").isEqualTo(ErrorCode.CONFLICT);
            softly.assertThat(jdbc.queryForObject("select lot_status from lot_master where lot_id=?", String.class, lot.get()))
                .isEqualTo("quarantined");
            softly.assertThat(jdbc.queryForObject("select inventory_status from inventory where inventory_id=?", String.class, stock.get()))
                .isEqualTo("quarantined");
            softly.assertThat(jdbc.queryForObject("select count(*) from inventory_transaction where reference_type='lot_inspection_release' and reference_id=?",
                Integer.class, lot.get())).isZero();
        });
    }

    private QualityInspectionCreateRequest inspection(String lot, String standard, boolean fail) {
        return new QualityInspectionCreateRequest(DEMO_PROJECT, null, lot, null, "Visual", fail ? "fail" : "pass",
            fail ? new BigDecimal("20") : BigDecimal.ONE, null, null, null, null, fail, standard);
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

    private record Result(boolean saved, ErrorCode error) {
    }
}
