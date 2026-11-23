package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** BOM inserts in this class are restricted to the disposable Testcontainers DB. */
@AutoConfigureMockMvc
class BomDraftConcurrencyIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void severalDraftsCanBePreparedWithoutReplacingEachOtherOrTheApprovedSource() throws Exception {
        String source=approved();String one=revision(source);String two=revision(source);
        call(get("/boms/"+one),null).andExpect(status().isOk()).andExpect(jsonPath("$.data.bomVersion").value(2));
        call(get("/boms/"+two),null).andExpect(status().isOk()).andExpect(jsonPath("$.data.bomVersion").value(3));
        call(get("/boms/"+source),null).andExpect(jsonPath("$.data.bomStatus").value("approved"));
        assertEquals(2,jdbc.queryForObject("select count(*) from bom_header where bom_status='draft' and target_item_id=(select target_item_id from bom_header where bom_id=?)",Integer.class,source));
    }
    @Test
    void onlyOneRevisionMayWaitForApprovalAndRejectionFreesTheSlot() throws Exception {
        String source=approved();String one=revision(source);String two=revision(source);
        call(post("/boms/"+one+"/submit"),null).andExpect(status().isOk());
        call(post("/boms/"+two+"/submit"),null).andExpect(status().isConflict());
        call(get("/boms/"+two),null).andExpect(jsonPath("$.data.bomStatus").value("draft"));
        call(post("/boms/"+one+"/reject"),Map.of("note","Revise material plan")).andExpect(status().isOk());
        call(post("/boms/"+two+"/submit"),null).andExpect(status().isOk());
    }
    @Test
    @Timeout(45)
    void simultaneousRevisionCreationUsesDistinctVersionNumbers() throws Exception {
        String source=approved();var pool=Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(()->call(post("/boms/"+source+"/revisions"),null).andReturn().getResponse().getStatus());
            var two=pool.submit(()->call(post("/boms/"+source+"/revisions"),null).andReturn().getResponse().getStatus());
            assertEquals(200,one.get(15,TimeUnit.SECONDS));assertEquals(200,two.get(15,TimeUnit.SECONDS));
            assertEquals(3,jdbc.queryForObject("select max(bom_version) from bom_header where target_item_id=(select target_item_id from bom_header where bom_id=?)",Integer.class,source));
        }finally{pool.shutdownNow();}
    }
    @Test
    @Timeout(45)
    void simultaneousSubmissionsCannotBothBecomePending() throws Exception {
        String source=approved();String one=revision(source);String two=revision(source);var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->call(post("/boms/"+one+"/submit"),null).andReturn().getResponse().getStatus());
            var b=pool.submit(()->call(post("/boms/"+two+"/submit"),null).andReturn().getResponse().getStatus());
            assertEquals(609,a.get(15,TimeUnit.SECONDS)+b.get(15,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("select count(*) from bom_header where bom_status='pending_approval' and target_item_id=(select target_item_id from bom_header where bom_id=?)",Integer.class,source));
        }finally{pool.shutdownNow();}
    }
    @Test
    void deletingADraftDoesNotReuseItsVersionAndCopiedDatesRemainExplicit() throws Exception {
        String source=approved();jdbc.update("update bom_header set effective_from='2030-01-01',effective_to='2030-01-31' where bom_id=?",source);
        String one=revision(source);call(delete("/boms/"+one),null).andExpect(status().isOk());
        String two=revision(source);call(get("/boms/"+two),null).andExpect(jsonPath("$.data.bomVersion").value(3));
        call(get("/boms/"+two+"/effectivity"),null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.effectiveFrom").value("2030-01-01"))
            .andExpect(jsonPath("$.data.periodVersion").value(0));
    }
    @Test
    @Timeout(45)
    void concurrentApprovalsCannotCommitAMutuallyRecursiveItemGraph() throws Exception {
        String a=item(),b=item();String one=draft(a,b),two=draft(b,a);
        call(post("/boms/"+one+"/submit"),null).andExpect(status().isOk());
        call(post("/boms/"+two+"/submit"),null).andExpect(status().isOk());
        // Keep the first approval's write uncommitted while the second validates the item graph.
        jdbc.execute("create function test_bom_approval_gate() returns trigger language plpgsql as $$ begin if NEW.bom_status='approved' then perform pg_advisory_xact_lock(90706001); end if; return NEW; end $$");
        jdbc.execute("create trigger test_bom_approval_gate before update on bom_header for each row execute function test_bom_approval_gate()");
        var pool=Executors.newFixedThreadPool(2);
        try {
            var requests=new TransactionTemplate(transactionManager).execute(transaction->{
                jdbc.queryForObject("select pg_advisory_xact_lock(90706001)",Object.class);
                var first=pool.submit(()->call(post("/boms/"+one+"/approve"),null).andReturn().getResponse().getStatus());
                int holding=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                DatabaseContention.awaitWaitingOrDone(jdbc,first,holding);
                var second=pool.submit(()->call(post("/boms/"+two+"/approve"),null).andReturn().getResponse().getStatus());
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while(jdbc.queryForObject("select count(distinct pid) from pg_locks where locktype='advisory' and not granted and pid<>?",Integer.class,holding)<2) {
                    if(first.isDone() || second.isDone() || System.nanoTime()>=deadline)
                        throw new AssertionError("Both approvals must reach a database lock before the first may commit.");
                    try {Thread.sleep(20);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                }
                return java.util.List.of(first,second);
            });
            assertEquals(600,requests.get(0).get(15,TimeUnit.SECONDS)+requests.get(1).get(15,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("select count(*) from bom_header where bom_id in (?,?) and bom_status='approved'",Integer.class,one,two));
        }finally{pool.shutdownNow();jdbc.execute("drop trigger test_bom_approval_gate on bom_header");jdbc.execute("drop function test_bom_approval_gate()");}
    }
    @Test
    @Timeout(45)
    void simultaneousMaterialImportsRecheckExistingLinesAfterAcquiringTheDraftLock() throws Exception {
        String source=approved(),id=revision(source),child=item();
        String code=jdbc.queryForObject("select item_code from item where item_id=?",String.class,child);
        Map<String,Object> body=Map.of("dryRun",false,"replace",false,"rows",java.util.List.of(Map.of("itemCode",code,"quantity","1","unit","kg")));
        String target=jdbc.queryForObject("select target_item_id from bom_header where bom_id=?",String.class,id);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var requests=new TransactionTemplate(transactionManager).execute(transaction->{
                jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"bom-effectivity|"+DEMO_PROJECT+"|"+target);
                var a=pool.submit(()->data(call(post("/boms/"+id+"/lines/import"),body)));
                var b=pool.submit(()->data(call(post("/boms/"+id+"/lines/import"),body)));
                int holding=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while(jdbc.queryForObject("select count(distinct pid) from pg_locks where locktype='advisory' and not granted and pid<>?",Integer.class,holding)<2) {
                    if(a.isDone() || b.isDone() || System.nanoTime()>=deadline)
                        throw new AssertionError("Both imports must wait before either can write.");
                    try{Thread.sleep(20);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                }
                return java.util.List.of(a,b);
            });
            int applied=0;for(var request:requests)if(request.get(15,TimeUnit.SECONDS).path("applied").asBoolean())applied++;
            assertEquals(1,applied);assertEquals(1,jdbc.queryForObject("select count(*) from bom_line where bom_id=? and child_item_id=?",Integer.class,id,child));
        }finally{pool.shutdownNow();}
    }
    @Test
    void revisionRequestIdReplaysTheCreatedDraftEvenAfterAnotherCopyAndRejectsMalformedIds() throws Exception {
        String source=approved();var command=Map.of("requestId",UUID.randomUUID().toString());
        String first=data(call(post("/boms/"+source+"/revisions"),command)).path("bomId").asText();
        revision(source);
        String replay=data(call(post("/boms/"+source+"/revisions"),command)).path("bomId").asText();
        assertEquals(first,replay);
        assertEquals(3,jdbc.queryForObject("select count(*) from bom_header where target_item_id=(select target_item_id from bom_header where bom_id=?)",Integer.class,source));
        call(post("/boms/"+source+"/revisions"),Map.of("requestId","not-a-uuid")).andExpect(status().isBadRequest());
    }
    @Test
    @Timeout(45)
    void simultaneousExactRevisionRequestsReturnOneDraftAndAnotherActorCannotReplayIt() throws Exception {
        String source=approved();var command=Map.of("requestId",UUID.randomUUID().toString());var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->data(call(post("/boms/"+source+"/revisions"),command)).path("bomId").asText());
            var b=pool.submit(()->data(call(post("/boms/"+source+"/revisions"),command)).path("bomId").asText());
            assertEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
        String editor="draft-"+UUID.randomUUID().toString().substring(0,8);
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?",editor,editor+"@test.local",DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,'editor')",UUID.randomUUID().toString(),DEMO_PROJECT,editor);
        mvc.perform(post("/boms/"+source+"/revisions").header("Authorization","Bearer "+jwt.generateAccessToken(editor))
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(command))).andExpect(status().isConflict());
        assertEquals(2,jdbc.queryForObject("select count(*) from bom_header where target_item_id=(select target_item_id from bom_header where bom_id=?)",Integer.class,source));
    }
    @Test
    void replayCannotResurrectADeletedCreatedDraft() throws Exception {
        String source=approved();var command=Map.of("requestId",UUID.randomUUID().toString());
        String draft=data(call(post("/boms/"+source+"/revisions"),command)).path("bomId").asText();
        call(delete("/boms/"+draft),null).andExpect(status().isOk());
        call(post("/boms/"+source+"/revisions"),command).andExpect(status().isConflict());
        assertEquals(2,jdbc.queryForObject("select count(*) from bom_header where target_item_id=(select target_item_id from bom_header where bom_id=?)",Integer.class,source));
    }
    private String draft(String product,String material)throws Exception {
        String id=data(call(post("/boms"),Map.of("projectId",DEMO_PROJECT,"targetItemId",product,"bomName","Graph test","baseQuantity",1,"baseUnit","kg"))).path("bomId").asText();
        call(post("/boms/"+id+"/lines"),Map.of("childItemId",material,"quantity",1,"unit","kg")).andExpect(status().isOk());return id;
    }
    private String approved()throws Exception {
        String product=item(),material=item();
        String id=data(call(post("/boms"),Map.of("projectId",DEMO_PROJECT,"targetItemId",product,"bomName","Parallel formula","baseQuantity",1,"baseUnit","kg"))).path("bomId").asText();
        call(post("/boms/"+id+"/lines"),Map.of("childItemId",material,"quantity",1,"unit","kg")).andExpect(status().isOk());
        call(post("/boms/"+id+"/submit"),null).andExpect(status().isOk());call(post("/boms/"+id+"/approve"),null).andExpect(status().isOk());return id;
    }
    private String item(){String id=UUID.randomUUID().toString();jdbc.update("insert into item(item_id,project_id,item_code,item_name,unit_id) values(?,?,?,?,?)",id,DEMO_PROJECT,"DRAFT-"+id.substring(0,8),"Draft test","unit_kg");return id;}
    private String revision(String source)throws Exception{return data(call(post("/boms/"+source+"/revisions"),null)).path("bomId").asText();}
    private JsonNode data(ResultActions result)throws Exception{return mapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");}
    private ResultActions call(MockHttpServletRequestBuilder request,Map<String,?> body)throws Exception{
        request.header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER));if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));return mvc.perform(request);
    }
}
