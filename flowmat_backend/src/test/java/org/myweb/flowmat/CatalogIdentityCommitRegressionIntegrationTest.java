package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.catalog.api.dto.request.ItemCreateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.request.ItemUpdateRequest;
import org.myweb.flowmat.domain.catalog.application.ItemService;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Competing service checks must not allow conflicting active identities to commit. */
class CatalogIdentityCommitRegressionIntegrationTest extends IntegrationTestSupport {

    @Autowired ItemService items;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtProvider jwt;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void competingCreatesCannotCommitTheSameActiveItemCode() throws Exception {
        String code = "RACE-" + tag();
        List<Boolean> committed = commitTogether(() -> items.createItem(request(code, null)), () -> items.createItem(request(code, null)));
        long stored = jdbc.queryForObject("select count(*) from item where project_id=? and item_code=? and deleted_yn='N'",
            Long.class, DEMO_PROJECT, code);
        assertThat(stored).as("Only one active item may own a project code after concurrent commits").isEqualTo(1);
        assertThat(committed.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
    }

    @Test
    void differentCodesCannotCommitTheSameActiveBarcode() throws Exception {
        String barcode = "BAR-" + tag();
        List<Boolean> committed = commitTogether(() -> items.createItem(request("A-" + tag(), barcode)),
            () -> items.createItem(request("B-" + tag(), barcode)));
        long stored = jdbc.queryForObject("select count(*) from item where project_id=? and barcode=? and deleted_yn='N'",
            Long.class, DEMO_PROJECT, barcode);
        assertThat(stored).as("Only one active item may own a project barcode after concurrent commits").isEqualTo(1);
        assertThat(committed.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
    }

    @Test
    void competingRenamesCannotCommitTheSameActiveItemCode() throws Exception {
        String first = create(request("A-" + tag(), null));
        String second = create(request("B-" + tag(), null));
        String code = "RENAMED-" + tag();
        ItemUpdateRequest update = mapper.convertValue(Map.of("itemCode", code), ItemUpdateRequest.class);
        List<Boolean> committed = commitTogether(() -> items.updateItem(first, update), () -> items.updateItem(second, update));
        assertThat(jdbc.queryForObject("select count(*) from item where project_id=? and item_code=? and deleted_yn='N'",
            Long.class, DEMO_PROJECT, code)).isEqualTo(1);
        assertThat(committed.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
    }

    @Test
    void competingEditsCannotCommitTheSameActiveBarcode() throws Exception {
        String first = create(request("A-" + tag(), null));
        String second = create(request("B-" + tag(), null));
        String barcode = "EDIT-" + tag();
        ItemUpdateRequest update = mapper.convertValue(Map.of("details", Map.of("barcode", barcode)), ItemUpdateRequest.class);
        List<Boolean> committed = commitTogether(() -> items.updateItem(first, update), () -> items.updateItem(second, update));
        assertThat(jdbc.queryForObject("select count(*) from item where project_id=? and barcode=? and deleted_yn='N'",
            Long.class, DEMO_PROJECT, barcode)).isEqualTo(1);
        assertThat(committed.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
    }

    private String create(ItemCreateRequest request) {
        SecurityContextHolder.getContext().setAuthentication(jwt.getAuthentication(jwt.generateAccessToken(DEMO_OWNER)));
        try {
            return items.createItem(request).itemId();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private List<Boolean> commitTogether(Runnable first, Runnable second) throws Exception {
        CyclicBarrier beforeCommit = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = executor.submit(() -> runInTransaction(first, beforeCommit));
            Future<Boolean> b = executor.submit(() -> runInTransaction(second, beforeCommit));
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private boolean runInTransaction(Runnable operation, CyclicBarrier beforeCommit) {
        SecurityContextHolder.getContext().setAuthentication(jwt.getAuthentication(jwt.generateAccessToken(DEMO_OWNER)));
        try {
            return Boolean.TRUE.equals(new TransactionTemplate(transactions).execute(status -> {
                operation.run();
                try {
                    // Both service checks finish before either transaction is allowed to commit.
                    beforeCommit.await(15, TimeUnit.SECONDS);
                } catch (Exception error) {
                    throw new IllegalStateException("Catalog requests did not reach the commit barrier.", error);
                }
                return true;
            }));
        } catch (DataIntegrityViolationException conflict) {
            return false;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private ItemCreateRequest request(String code, String barcode) {
        Map<String, Object> input = barcode == null
            ? Map.of("projectId", DEMO_PROJECT, "itemCode", code, "itemName", code)
            : Map.of("projectId", DEMO_PROJECT, "itemCode", code, "itemName", code,
                "details", Map.of("barcode", barcode));
        return mapper.convertValue(input, ItemCreateRequest.class);
    }

    private static String tag() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
