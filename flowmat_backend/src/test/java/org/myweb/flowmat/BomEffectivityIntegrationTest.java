package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Fixtures live only in IntegrationTestSupport's disposable Testcontainers DB; never the dev/session DB. */
@AutoConfigureMockMvc
class BomEffectivityIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void ownerChangesAnApprovedIntervalAndHistoryKeepsTheOriginalDatesAndActor() throws Exception {
        String id = bom("approved", null, 1);
        Map<String,Object> body = command(); body.put("changedBy", "forged");
        call(post(path(id)), body, DEMO_OWNER).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.effectiveFrom").value("2030-01-01"))
            .andExpect(jsonPath("$.data.effectiveTo").value("2030-01-31"))
            .andExpect(jsonPath("$.data.periodVersion").value(1))
            .andExpect(jsonPath("$.data.history[0].changedBy").value(DEMO_OWNER))
            .andExpect(jsonPath("$.data.history[0].previousEffectiveFrom").isEmpty())
            .andExpect(jsonPath("$.data.history[0].reason").value("Engineering change"));
        assertEquals("approved", jdbc.queryForObject("select bom_status from bom_header where bom_id = ?", String.class, id));
    }

    @Test
    void onlyAnOwnerChangesApprovedDatesButAnEditorCanPlanDraftDates() throws Exception {
        String approved = bom("approved", null, 1); String draft = bom("draft", null, 1);
        for (String role : new String[]{"editor", "viewer", "outsider"}) {
            String user = user(role);
            call(post(path(approved)), command(), user).andExpect(status().isForbidden());
            call(get(path(approved)), null, user).andExpect("outsider".equals(role) ? status().isForbidden() : status().isOk());
            call(post(path(draft)), command(), user).andExpect("editor".equals(role) ? status().isOk() : status().isForbidden());
        }
    }

    @Test
    void pendingAndRetiredPeriodsCannotBeChanged() throws Exception {
        for (String state : new String[]{"pending_approval", "retired"})
            call(post(path(bom(state,null,1))), command(), DEMO_OWNER).andExpect(status().isConflict());
    }

    @Test
    void anOverlapIncludingASharedBoundaryIsRejectedButAdjacentDaysAreAllowed() throws Exception {
        String first = bom("approved", null, 1);
        call(post(path(first)), command(), DEMO_OWNER).andExpect(status().isOk());
        String target = jdbc.queryForObject("select target_item_id from bom_header where bom_id = ?", String.class, first);
        String next = bom("approved", target, 2);
        Map<String,Object> overlap = command(); overlap.put("effectiveFrom", "2030-01-31"); overlap.put("effectiveTo", "2030-02-28");
        call(post(path(next)), overlap, DEMO_OWNER).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message", containsString("effectiveFrom/effectiveTo")));
        overlap.put("effectiveFrom", "2030-02-01");
        call(post(path(next)), overlap, DEMO_OWNER).andExpect(status().isOk());
        assertEquals(0, jdbc.queryForObject("select count(*) from bom_header where target_item_id = ? and bom_status='retired'", Integer.class, target));
    }

    @Test
    void draftsMayOverlapAndAnOpenBoundaryIsExplicit() throws Exception {
        String approved = bom("approved", null, 1);
        String target = jdbc.queryForObject("select target_item_id from bom_header where bom_id = ?", String.class, approved);
        String draft = bom("draft", target, 2);
        Map<String,Object> body = command(); body.put("effectiveTo", null);
        call(post(path(draft)), body, DEMO_OWNER).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.effectiveTo").isEmpty());
        body.put("effectiveFrom", null); body.put("expectedPeriodVersion", 1); body.put("requestId", UUID.randomUUID());
        call(post(path(draft)), body, DEMO_OWNER).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.periodVersion").value(2));
    }

    @Test
    void staleVersionNoOpInvalidReasonAndMalformedDatesNeverChangeThePeriod() throws Exception {
        String id = bom("draft",null,1);
        Map<String,Object> body = command(); body.put("expectedPeriodVersion", 1);
        call(post(path(id)), body, DEMO_OWNER).andExpect(status().isConflict());
        body = command(); body.put("effectiveFrom", null); body.put("effectiveTo", null);
        call(post(path(id)), body, DEMO_OWNER).andExpect(status().isBadRequest());
        for (String field : new String[]{"effectiveFrom", "effectiveTo"}) {
            body=command(); body.put(field,"bad-date"); call(post(path(id)),body,DEMO_OWNER).andExpect(status().isBadRequest());
        }
        body=command();body.put("effectiveTo","2029-12-31");call(post(path(id)),body,DEMO_OWNER).andExpect(status().isBadRequest());
        for (String reason : new String[]{" ", "x".repeat(1001), "bad\u0000reason"}) {
            body=command();body.put("reason",reason);call(post(path(id)),body,DEMO_OWNER).andExpect(status().isBadRequest());
        }
        body=command();body.put("requestId","invalid");call(post(path(id)),body,DEMO_OWNER).andExpect(status().isBadRequest());
        assertEquals(0,count(id));
    }

    @Test
    void retryIsBoundToTheOriginalPayloadAndReplayDoesNotOverwriteANewerPeriod() throws Exception {
        String id=bom("draft",null,1);Map<String,Object> first=command();
        call(post(path(id)),first,DEMO_OWNER).andExpect(status().isOk());
        Map<String,Object> second=command();second.put("expectedPeriodVersion",1);second.put("effectiveTo","2030-02-28");
        call(post(path(id)),second,DEMO_OWNER).andExpect(status().isOk());
        call(post(path(id)),first,DEMO_OWNER).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.effectiveTo").value("2030-02-28"))
            .andExpect(jsonPath("$.data.periodVersion").value(2));
        first.put("reason","Different request");call(post(path(id)),first,DEMO_OWNER).andExpect(status().isConflict());
        assertEquals(2,count(id));
    }

    @Test
    @Timeout(45)
    void concurrentRetriesCreateOneHistoryAndCompetingVersionsCannotBothWin() throws Exception {
        String id=bom("draft",null,1);Map<String,Object> body=command();var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->call(post(path(id)),body,DEMO_OWNER).andReturn().getResponse().getStatus());
            var b=pool.submit(()->call(post(path(id)),body,DEMO_OWNER).andReturn().getResponse().getStatus());
            assertEquals(200,a.get(15,TimeUnit.SECONDS));assertEquals(200,b.get(15,TimeUnit.SECONDS));assertEquals(1,count(id));
            Map<String,Object> one=command();one.put("expectedPeriodVersion",1);one.put("effectiveTo","2030-02-28");
            Map<String,Object> two=command();two.put("expectedPeriodVersion",1);two.put("effectiveTo","2030-03-31");
            a=pool.submit(()->call(post(path(id)),one,DEMO_OWNER).andReturn().getResponse().getStatus());
            b=pool.submit(()->call(post(path(id)),two,DEMO_OWNER).andReturn().getResponse().getStatus());
            assertEquals(609,a.get(15,TimeUnit.SECONDS)+b.get(15,TimeUnit.SECONDS));assertEquals(2,count(id));
        } finally {pool.shutdownNow();}
    }

    @Test
    void historyFailureRollsBackTheHeaderChange() throws Exception {
        String id=bom("approved",null,1);
        jdbc.execute("alter table bom_effectivity_change add constraint test_effectivity_rollback check (reason <> 'force-rollback')");
        try {
            Map<String,Object> body=command();body.put("reason","force-rollback");
            call(post(path(id)),body,DEMO_OWNER).andExpect(status().isConflict());
            assertEquals(0,count(id));
            assertEquals(0,jdbc.queryForObject("select effective_period_version from bom_header where bom_id = ?",Long.class,id));
        } finally {jdbc.execute("alter table bom_effectivity_change drop constraint test_effectivity_rollback");}
    }

    @Test
    void bothNullableBoundariesMustBeExplicitAndOutOfRangeYearsAreRejected() throws Exception {
        String id=bom("draft",null,1);
        for(String field:new String[]{"effectiveFrom","effectiveTo"}) {
            Map<String,Object> body=command();body.remove(field);
            call(post(path(id)),body,DEMO_OWNER).andExpect(status().isBadRequest());
            body=command();body.put(field,"0000-01-01");
            call(post(path(id)),body,DEMO_OWNER).andExpect(status().isBadRequest());
        }
        assertEquals(0,count(id));
    }

    @Test
    @Timeout(45)
    void twoApprovedRevisionsCannotConcurrentlyExpandIntoAnOverlappingWindow() throws Exception {
        String first=bom("approved",null,1);
        String target=jdbc.queryForObject("select target_item_id from bom_header where bom_id=?",String.class,first);
        String second=bom("approved",target,2);
        jdbc.update("update bom_header set effective_from='2030-01-01',effective_to='2030-01-31' where bom_id=?",first);
        jdbc.update("update bom_header set effective_from='2030-03-01',effective_to='2030-03-31' where bom_id=?",second);
        Map<String,Object> one=command();one.put("effectiveTo","2030-02-20");
        Map<String,Object> two=command();two.put("effectiveFrom","2030-02-01");two.put("effectiveTo","2030-03-31");
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->call(post(path(first)),one,DEMO_OWNER).andReturn().getResponse().getStatus());
            var b=pool.submit(()->call(post(path(second)),two,DEMO_OWNER).andReturn().getResponse().getStatus());
            assertEquals(609,a.get(15,TimeUnit.SECONDS)+b.get(15,TimeUnit.SECONDS));assertEquals(1,count(first)+count(second));
        } finally {pool.shutdownNow();}
    }

    @Test
    void anExplicitCalendarDateSelectsOnlyTheApprovedRevisionCoveringThatDay() throws Exception {
        String first=bom("approved",null,1);
        String target=jdbc.queryForObject("select target_item_id from bom_header where bom_id=?",String.class,first);
        String next=bom("approved",target,2);
        bom("draft",target,3);bom("retired",target,4);
        jdbc.update("update bom_header set effective_from='2030-01-01',effective_to='2030-01-31' where bom_id=?",first);
        jdbc.update("update bom_header set effective_from='2030-02-01',effective_to='2030-02-28' where bom_id=?",next);
        call(get("/boms/effective").param("projectId",DEMO_PROJECT).param("targetItemId",target).param("on","2030-01-31"),null,DEMO_OWNER)
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.bomId").value(first));
        call(get("/boms/effective").param("projectId",DEMO_PROJECT).param("targetItemId",target).param("on","2030-02-01"),null,DEMO_OWNER)
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.bomId").value(next));
        call(get("/boms/effective").param("projectId",DEMO_PROJECT).param("targetItemId",target).param("on","2030-03-01"),null,DEMO_OWNER)
            .andExpect(status().isNotFound());
        jdbc.update("update bom_header set deleted_yn='Y' where bom_id=?",next);
        call(get("/boms/effective").param("projectId",DEMO_PROJECT).param("targetItemId",target).param("on","2030-02-01"),null,DEMO_OWNER)
            .andExpect(status().isNotFound());
    }

    @Test
    void anAmbiguousLegacyPeriodIsAConflictAndDateQueriesRequireReadAccess() throws Exception {
        String first=bom("approved",null,1);
        String target=jdbc.queryForObject("select target_item_id from bom_header where bom_id=?",String.class,first);
        bom("approved",target,2);
        call(get("/boms/effective").param("projectId",DEMO_PROJECT).param("targetItemId",target).param("on","2030-01-01"),null,DEMO_OWNER)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message",containsString("effectiveFrom/effectiveTo")));
        call(get("/boms/effective").param("projectId",DEMO_PROJECT).param("targetItemId",target).param("on","2030-01-01"),null,user("outsider"))
            .andExpect(status().isForbidden());
        for(String date:new String[]{"bad-date","0000-01-01"})
            call(get("/boms/effective").param("projectId",DEMO_PROJECT).param("targetItemId",target).param("on",date),null,DEMO_OWNER)
                .andExpect(status().isBadRequest());
        call(get("/boms/effective").param("projectId",DEMO_PROJECT).param("targetItemId",target),null,DEMO_OWNER)
            .andExpect(status().isBadRequest());
    }

    private String bom(String state,String target,int version) {
        if(target==null) {
            target=UUID.randomUUID().toString();
            jdbc.update("insert into item(item_id,project_id,item_code,item_name,unit_id) values(?,?,?,?,?)",target,DEMO_PROJECT,"EFF-"+target.substring(0,8),"Effectivity test","unit_ea");
        }
        String id=UUID.randomUUID().toString();
        jdbc.update("insert into bom_header(bom_id,project_id,target_item_id,bom_name,bom_version,base_quantity,base_unit,bom_status,approval_status,created_by) values(?,?,?,?,?,1,'ea',?,?,?)",id,DEMO_PROJECT,target,"Effectivity test",version,state,state,DEMO_OWNER);
        return id;
    }
    private String user(String role) {
        String id="effect-"+UUID.randomUUID().toString().substring(0,8);
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id = ?",id,id+"@test.local",DEMO_OWNER);
        if(!"outsider".equals(role))jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",UUID.randomUUID().toString(),DEMO_PROJECT,id,role);
        return id;
    }
    private Map<String,Object> command(){return new LinkedHashMap<>(Map.of("requestId",UUID.randomUUID(),"effectiveFrom","2030-01-01","effectiveTo","2030-01-31","expectedPeriodVersion",0,"reason"," Engineering change "));}
    private String path(String id){return "/boms/"+id+"/effectivity";}
    private int count(String id){return jdbc.queryForObject("select count(*) from bom_effectivity_change where bom_id = ?",Integer.class,id);}
    private ResultActions call(MockHttpServletRequestBuilder request,Map<String,?> body,String user)throws Exception{
        request.header("Authorization","Bearer "+jwt.generateAccessToken(user));
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));return mvc.perform(request);
    }
}
