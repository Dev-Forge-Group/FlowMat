package org.myweb.flowmat;



import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import org.myweb.flowmat.domain.bom.api.dto.request.BomCreateRequest;
import org.myweb.flowmat.domain.bom.api.dto.request.BomCopyRequest;
import org.myweb.flowmat.domain.bom.api.dto.request.BomLineImportRequest;

import org.myweb.flowmat.domain.bom.api.dto.request.BomLineCreateRequest;

import org.myweb.flowmat.domain.bom.application.BomRevisionLock;

import org.myweb.flowmat.domain.bom.application.BomService;
import org.myweb.flowmat.domain.bom.application.BomCopyService;
import org.myweb.flowmat.domain.bom.application.BomLineImportService;
import org.myweb.flowmat.domain.bom.api.dto.response.BomResponse;

import org.myweb.flowmat.domain.catalog.application.ItemService;
import org.myweb.flowmat.domain.catalog.api.dto.request.ItemUpdateRequest;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;

import org.myweb.flowmat.global.exception.BusinessException;

import org.myweb.flowmat.global.exception.ErrorCode;

import org.myweb.flowmat.global.id.IdGenerator;

import org.myweb.flowmat.global.security.JwtProvider;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.security.core.context.SecurityContextHolder;

import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;

import org.springframework.transaction.support.TransactionTemplate;



/** Runs only against IntegrationTestSupport's disposable Postgres and Redis. */

@Timeout(120)

class BomItemLifecycleConcurrencyRegressionIntegrationTest extends IntegrationTestSupport {

    @Autowired private BomService boms;
    @Autowired private BomCopyService copies;
    @Autowired private BomLineImportService imports;
    @Autowired private CatalogQuery catalog;
    @Autowired private ObjectMapper mapper;

    @Autowired private ItemService items;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private JwtProvider jwt;

    @Autowired private PlatformTransactionManager transactions;

    @MockitoSpyBean private BomRevisionLock revisions;

    @MockitoSpyBean private IdGenerator ids;



    @Test

    void creatingABomAndDeletingItsTargetCannotBothCommit() throws Exception {

        String item = item();

        BomCreateRequest request = new BomCreateRequest(DEMO_PROJECT, item, "Lifecycle draft", BigDecimal.ONE, "kg", null);

        CountDownLatch validated = new CountDownLatch(1);

        CountDownLatch resume = new CountDownLatch(1);

        CountDownLatch deleting = new CountDownLatch(1);

        AtomicReference<Thread> creator = new AtomicReference<>();

        AtomicReference<Integer> deletingPid = new AtomicReference<>();

        BomRevisionLock revisionTarget = AopTestUtils.getUltimateTargetObject(revisions);
        var delegated = mockingDetails(revisionTarget).getMockCreationSettings().getDefaultAnswer();

        doAnswer(call -> {

            Object answer = delegated.answer(call);

            if (Thread.currentThread() == creator.get()) {

                // createBom has already performed its real target lookup and active-item check.

                validated.countDown();

                assertThat(resume.await(45, TimeUnit.SECONDS)).as("the validated BOM creation was released").isTrue();

            }

            return answer;

        }).when(revisionTarget).lockItem(DEMO_PROJECT, item);



        Result created;

        Result deleted;

        var pool = Executors.newFixedThreadPool(2);

        try {

            var create = pool.submit(() -> {

                creator.set(Thread.currentThread());

                return asOwner(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {

                    jdbc.execute("set local lock_timeout='15s'");

                    jdbc.execute("set local statement_timeout='30s'");

                    boms.createBom(request);

                }));

            });

            if (!validated.await(20, TimeUnit.SECONDS)) {

                if (create.isDone()) create.get(1, TimeUnit.SECONDS);

                throw new AssertionError("BOM creation did not finish its real target validation and revision lock.");

            }

            var remove = pool.submit(() -> asOwner(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {

                jdbc.execute("set local lock_timeout='15s'");

                jdbc.execute("set local statement_timeout='30s'");

                deletingPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));

                deleting.countDown();

                items.deleteItem(item);

            })));

            assertThat(deleting.await(10, TimeUnit.SECONDS)).as("the deletion transaction started").isTrue();

            boolean waiting = false;

            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

            while (!remove.isDone() && !waiting && System.nanoTime() < until) {

                waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where pid=? and wait_event_type='Lock'",

                    Integer.class, deletingPid.get()) > 0;

                if (!waiting) Thread.sleep(20);

            }

            assertThat(remove.isDone() || waiting).as("deletion committed/refused or reached a real database lock").isTrue();

            resume.countDown();

            created = create.get(20, TimeUnit.SECONDS);

            deleted = remove.get(20, TimeUnit.SECONDS);

        } finally {

            resume.countDown();

            pool.shutdownNow();

            assertThat(pool.awaitTermination(35, TimeUnit.SECONDS)).isTrue();

        }



        assertThat(created.saved() && deleted.saved())

            .as("creating a BOM and deleting its validated target cannot both commit: BOM=%s, deletion=%s", created, deleted)

            .isFalse();

        assertThat(jdbc.queryForObject("select count(*) from bom_header b join item i on i.item_id=b.target_item_id "

            + "where b.target_item_id=? and b.deleted_yn='N' and i.deleted_yn='Y'", Integer.class, item)).isZero();

        if (created.saved()) {

            assertThat(deleted.error()).isEqualTo(ErrorCode.CONFLICT);

            assertThat(deletedYn(item)).isEqualTo("N");

            assertThat(bomCount(item)).isEqualTo(1);

        } else {

            assertThat(deleted.saved()).isTrue();

            assertThat(created.error()).isIn(ErrorCode.BAD_REQUEST, ErrorCode.NOT_FOUND, ErrorCode.CONFLICT);

            assertThat(deletedYn(item)).isEqualTo("Y");

            assertThat(bomCount(item)).isZero();

        }

    }



    @Test

    void addingABomLineAndDeletingItsChildCannotBothCommit() throws Exception {

        String target = item();

        String child = item();

        AtomicReference<String> bomId = new AtomicReference<>();

        assertThat(asOwner(() -> bomId.set(boms.createBom(

            new BomCreateRequest(DEMO_PROJECT, target, "Lifecycle child draft", BigDecimal.ONE, "kg", null)).bomId()))

            .saved()).isTrue();

        BomLineCreateRequest request = new BomLineCreateRequest(child, BigDecimal.ONE, "kg", BigDecimal.ZERO,

            "N", null, 1, null, "material", false);

        CountDownLatch validated = new CountDownLatch(1);

        CountDownLatch resume = new CountDownLatch(1);

        CountDownLatch deleting = new CountDownLatch(1);

        AtomicReference<Thread> adder = new AtomicReference<>();

        AtomicReference<Integer> deletingPid = new AtomicReference<>();

        var delegated = mockingDetails(ids).getMockCreationSettings().getDefaultAnswer();

        doAnswer(call -> {

            Object answer = delegated.answer(call);

            if (Thread.currentThread() == adder.get()) {

                // addLine's first generated id follows its real child and quantity validation, before line save.

                validated.countDown();

                assertThat(resume.await(45, TimeUnit.SECONDS)).as("the validated BOM line addition was released").isTrue();

            }

            return answer;

        }).when(ids).generate();



        Result added;

        Result deleted;

        var pool = Executors.newFixedThreadPool(2);

        try {

            var add = pool.submit(() -> {

                adder.set(Thread.currentThread());

                return asOwner(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {

                    jdbc.execute("set local lock_timeout='15s'");

                    jdbc.execute("set local statement_timeout='30s'");

                    boms.addLine(bomId.get(), request);

                }));

            });

            if (!validated.await(20, TimeUnit.SECONDS)) {

                if (add.isDone()) add.get(1, TimeUnit.SECONDS);

                throw new AssertionError("BOM line addition did not finish its real child validation.");

            }

            var remove = pool.submit(() -> asOwner(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {

                jdbc.execute("set local lock_timeout='15s'");

                jdbc.execute("set local statement_timeout='30s'");

                deletingPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));

                deleting.countDown();

                items.deleteItem(child);

            })));

            assertThat(deleting.await(10, TimeUnit.SECONDS)).as("the child deletion transaction started").isTrue();

            boolean waiting = false;

            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

            while (!remove.isDone() && !waiting && System.nanoTime() < until) {

                waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where pid=? and wait_event_type='Lock'",

                    Integer.class, deletingPid.get()) > 0;

                if (!waiting) Thread.sleep(20);

            }

            assertThat(remove.isDone() || waiting).as("child deletion committed/refused or reached a real database lock").isTrue();

            resume.countDown();

            added = add.get(20, TimeUnit.SECONDS);

            deleted = remove.get(20, TimeUnit.SECONDS);

        } finally {

            resume.countDown();

            pool.shutdownNow();

            assertThat(pool.awaitTermination(35, TimeUnit.SECONDS)).isTrue();

        }



        assertThat(added.saved() && deleted.saved())

            .as("adding a BOM line and deleting its validated child cannot both commit: line=%s, deletion=%s", added, deleted)

            .isFalse();

        assertThat(jdbc.queryForObject("select count(*) from bom_line l join item i on i.item_id=l.child_item_id "

            + "where l.bom_id=? and l.child_item_id=? and i.deleted_yn='Y'", Integer.class, bomId.get(), child)).isZero();

        int lines = jdbc.queryForObject("select count(*) from bom_line where bom_id=? and child_item_id=?",

            Integer.class, bomId.get(), child);

        if (added.saved()) {

            assertThat(deleted.error()).isEqualTo(ErrorCode.CONFLICT);

            assertThat(deletedYn(child)).isEqualTo("N");

            assertThat(lines).isEqualTo(1);

        } else {

            assertThat(deleted.saved()).isTrue();

            assertThat(added.error()).isIn(ErrorCode.BAD_REQUEST, ErrorCode.NOT_FOUND, ErrorCode.CONFLICT);

            assertThat(deletedYn(child)).isEqualTo("Y");

            assertThat(lines).isZero();

        }

    }



    @Test
    void aTargetWriterReturnsAConflictBeforeCreatingTheBom() throws Exception {
        String target = item();
        Result result = whileItemWriteLocked(target, () -> asOwner(() -> boms.createBom(
            new BomCreateRequest(DEMO_PROJECT, target, "Locked target", BigDecimal.ONE, "kg", null))));
        assertThat(result.error()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(bomCount(target)).isZero();
    }

    @Test
    void aChildWriterReturnsAConflictWithoutAddingALine() throws Exception {
        String bom = draft(item());
        String child = item();
        Result result = whileItemWriteLocked(child, () -> asOwner(() -> boms.addLine(bom, line(child))));
        assertThat(result.error()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(jdbc.queryForObject("select count(*) from bom_line where bom_id=?", Integer.class, bom)).isZero();
    }

    @Test
    void twoBomLinesCanReferenceTheSameChildConcurrently() throws Exception {
        String firstBom = draft(item());
        String secondBom = draft(item());
        String child = item();
        CountDownLatch firstReady = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<Thread> firstThread = new AtomicReference<>();
        var delegated = mockingDetails(ids).getMockCreationSettings().getDefaultAnswer();
        doAnswer(call -> {
            Object answer = delegated.answer(call);
            if (Thread.currentThread() == firstThread.get()) {
                firstReady.countDown();
                assertThat(resume.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return answer;
        }).when(ids).generate();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                firstThread.set(Thread.currentThread());
                return asOwner(() -> boms.addLine(firstBom, line(child)));
            });
            assertThat(firstReady.await(15, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> asOwner(() -> boms.addLine(secondBom, line(child))));
            try {
                assertThat(second.get(15, TimeUnit.SECONDS).saved()).isTrue();
            } finally {
                resume.countDown();
            }
            assertThat(first.get(15, TimeUnit.SECONDS).saved()).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from bom_line where child_item_id=? and bom_id in (?,?)",
                Integer.class, child, firstBom, secondBom)).isEqualTo(2);
        } finally {
            resume.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(35, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void theReferenceGuardRequiresTheCallersTransaction() {
        assertThatThrownBy(() -> catalog.lockProjectItemForReference(DEMO_PROJECT, item()))
            .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aTargetMadeInactiveAfterInitialValidationIsNotUsed() throws Exception {
        String target = item();
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<Thread> creator = new AtomicReference<>();
        BomRevisionLock revisionTarget = AopTestUtils.getUltimateTargetObject(revisions);
        var delegated = mockingDetails(revisionTarget).getMockCreationSettings().getDefaultAnswer();
        doAnswer(call -> {
            Object answer = delegated.answer(call);
            if (Thread.currentThread() == creator.get()) {
                read.countDown();
                assertThat(resume.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return answer;
        }).when(revisionTarget).lockItem(DEMO_PROJECT, target);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var create = pool.submit(() -> {
                creator.set(Thread.currentThread());
                return asOwner(() -> boms.createBom(new BomCreateRequest(DEMO_PROJECT, target,
                    "Refreshed target", BigDecimal.ONE, "kg", null)));
            });
            assertThat(read.await(15, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(asOwner(() -> items.updateItem(target,
                    mapper.convertValue(Map.of("itemStatus", "inactive"), ItemUpdateRequest.class))).saved()).isTrue();
            } finally {
                resume.countDown();
            }
            assertThat(create.get(15, TimeUnit.SECONDS).error()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(bomCount(target)).isZero();
            assertThat(jdbc.queryForObject("select item_status from item where item_id=?", String.class, target)).isEqualTo("inactive");
        } finally {
            resume.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(35, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void aLateCopyConflictRollsBackItsHeaderAndEarlierMaterial() throws Exception {
        String source = draft(item());
        String first = item();
        String second = item();
        assertThat(asOwner(() -> boms.addLine(source, line(first))).saved()).isTrue();
        assertThat(asOwner(() -> boms.addLine(source, line(second, 2))).saved()).isTrue();
        BomResponse before = bom(source);
        assertThat(before.lines()).extracting(entry -> entry.childItemId()).containsExactly(first, second);
        String target = item();
        Result result = whileItemWriteLocked(second,
            () -> asOwner(() -> copies.copy(source, new BomCopyRequest(target, "Failed copy"))));
        assertThat(result.error()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(bomCount(target)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from bom_line l join bom_header h on h.bom_id=l.bom_id "
            + "where h.target_item_id=?", Integer.class, target)).isZero();
        assertThat(bom(source)).isEqualTo(before);
    }

    @Test
    void aLateCsvConflictRestoresReplacedLinesAndRemovesEarlierAdditions() throws Exception {
        String bom = draft(item());
        String old = item();
        String first = item();
        String second = item();
        assertThat(asOwner(() -> boms.addLine(bom, line(old))).saved()).isTrue();
        BomResponse before = bom(bom);
        BomLineImportRequest request = new BomLineImportRequest(false, true, List.of(
            new BomLineImportRequest.Row("BL-" + first, "1", "kg", null, "material"),
            new BomLineImportRequest.Row("BL-" + second, "1", "kg", null, "material")));
        Result result = whileItemWriteLocked(second, () -> asOwner(() -> imports.importLines(bom, request)));
        assertThat(result.error()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(bom(bom)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from bom_line where bom_id=? and child_item_id in (?,?)",
            Integer.class, bom, first, second)).isZero();
    }

    private Result whileItemWriteLocked(String item, Callable<Result> operation) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var writer = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.execute("set local lock_timeout='15s'");
                jdbc.execute("set local statement_timeout='30s'");
                jdbc.queryForObject("select item_id from item where item_id=? for update", String.class, item);
                locked.countDown();
                try {
                    assertThat(resume.await(40, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }));
            assertThat(locked.await(15, TimeUnit.SECONDS)).isTrue();
            Result result;
            try {
                result = pool.submit(operation).get(15, TimeUnit.SECONDS);
            } finally {
                resume.countDown();
            }
            writer.get(15, TimeUnit.SECONDS);
            return result;
        } finally {
            resume.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(35, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String draft(String target) {
        AtomicReference<String> id = new AtomicReference<>();
        assertThat(asOwner(() -> id.set(boms.createBom(new BomCreateRequest(DEMO_PROJECT, target,
            "Lifecycle draft", BigDecimal.ONE, "kg", null)).bomId())).saved()).isTrue();
        return id.get();
    }

    private BomResponse bom(String id) {
        AtomicReference<BomResponse> response = new AtomicReference<>();
        assertThat(asOwner(() -> response.set(boms.getBom(id))).saved()).isTrue();
        return response.get();
    }

    private static BomLineCreateRequest line(String child) {
        return line(child, 1);
    }

    private static BomLineCreateRequest line(String child, int order) {
        return new BomLineCreateRequest(child, BigDecimal.ONE, "kg", BigDecimal.ZERO, "N", null, order, null, "material", false);
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

        String id = UUID.randomUUID().toString();

        jdbc.update("insert into item(item_id,project_id,item_code,item_name,item_type,item_status,unit_id,unit_cost,deleted_yn) "

            + "values(?,?,?,'BOM lifecycle target','product','active','unit_kg',1,'N')", id, DEMO_PROJECT, "BL-" + id);

        return id;

    }



    private String deletedYn(String item) {

        return jdbc.queryForObject("select deleted_yn from item where item_id=?", String.class, item);

    }



    private int bomCount(String item) {

        return jdbc.queryForObject("select count(*) from bom_header where target_item_id=? and deleted_yn='N'", Integer.class, item);

    }



    private record Result(boolean saved, ErrorCode error) {

    }

}

