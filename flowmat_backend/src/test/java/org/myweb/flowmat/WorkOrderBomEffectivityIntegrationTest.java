package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** All BOM fixtures are confined to IntegrationTestSupport's disposable PostgreSQL. */
@AutoConfigureMockMvc
class WorkOrderBomEffectivityIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;

    @Test
    void projectCalendarDaySelectsAndStoresTheRevisionIncludingBoundaryDays() throws Exception {
        String item=item(); String january=bom(item,1,"approved","2030-01-01","2030-01-31");
        String february=bom(item,2,"approved","2030-02-01","2030-02-28");
        call(post("/work-orders"),order(item,null,"2030-01-31T14:59:59Z")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bomId").value(january));
        call(post("/work-orders"),order(item,null,"2030-01-31T15:00:00Z")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bomId").value(february));
    }

    @Test
    void aDraftDateChangeReselectsAndApprovalChecksTheStoredRevision() throws Exception {
        String item=item(); String first=bom(item,1,"approved","2030-01-01","2030-01-31");
        String second=bom(item,2,"approved","2030-02-01","2030-02-28");
        String id=id(call(post("/work-orders"),order(item,null,"2030-01-10T00:00:00Z")),"workOrderId");
        call(put("/work-orders/"+id),order(item,null,"2030-02-10T00:00:00Z")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bomId").value(second));
        call(post("/work-orders/"+id+"/approve"),null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bomId").value(second));
        assertEquals("approved",jdbc.queryForObject("select bom_status from bom_header where bom_id=?",String.class,first));
    }

    @Test
    void anExplicitApprovedRevisionMustCoverThePlannedProjectDate() throws Exception {
        String item=item(); String january=bom(item,1,"approved","2030-01-01","2030-01-31");
        call(post("/work-orders"),order(item,january,"2030-02-01T00:00:00Z")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message",containsString("bomId")))
            .andExpect(jsonPath("$.message",containsString("plannedStartAt")));
    }

    @Test
    void missingCoverageOrLegacyOverlapCannotSilentlyCreateABomlessPlan() throws Exception {
        String item=item(); bom(item,1,"approved","2030-01-01","2030-01-31");
        call(post("/work-orders"),order(item,null,"2030-02-01T00:00:00Z")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message",containsString("plannedStartAt")));
        bom(item,2,"approved","2030-01-01","2030-01-31");
        call(post("/work-orders"),order(item,null,"2030-01-10T00:00:00Z")).andExpect(status().isConflict());
    }

    @Test
    void productsWithoutApprovedBomsAndUndatedLegacyPlansRemainSupported() throws Exception {
        String item=item(); bom(item,1,"draft",null,null);
        call(post("/work-orders"),order(item,null,"2030-01-10T00:00:00Z")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bomId").isEmpty());
        String approved=bom(item,2,"approved",null,null);
        call(post("/work-orders"),order(item,approved,null)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bomId").value(approved));
        call(post("/work-orders"),order(item,null,null)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bomId").isEmpty());
    }

    @Test
    void approvalRevalidatesAnExplicitRevisionAfterItsPeriodChanges() throws Exception {
        String item=item(); String chosen=bom(item,1,"approved","2030-01-01","2030-01-31");
        String id=id(call(post("/work-orders"),order(item,chosen,"2030-01-10T00:00:00Z")),"workOrderId");
        jdbc.update("update bom_header set effective_from='2030-01-20' where bom_id=?",chosen);
        call(post("/work-orders/"+id+"/approve"),null).andExpect(status().isBadRequest());
        assertEquals("draft",jdbc.queryForObject("select work_order_status from work_order where work_order_id=?",String.class,id));
    }

    @Test
    void aRunInheritsTheStoredRevisionAndCannotOverrideIt() throws Exception {
        String item=item(); String chosen=bom(item,1,"approved","2030-01-01","2030-01-31");
        String other=bom(item,2,"approved","2030-02-01","2030-02-28");
        String id=id(call(post("/work-orders"),order(item,chosen,"2030-01-10T00:00:00Z")),"workOrderId");
        call(post("/work-orders/"+id+"/approve"),null).andExpect(status().isOk());
        var run=new LinkedHashMap<String,Object>(Map.of("projectId",DEMO_PROJECT,"workflowId",DEMO_WORKFLOW,"workOrderId",id,"plannedOutputQty",1));
        run.put("bomId",other);
        call(post("/production-runs/start"),run).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message",containsString("bomId")));
        run.remove("bomId");
        call(post("/production-runs/start"),run).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bomId").value(chosen));
    }

    @Test
    void approvedReschedulePreservesRevisionAndRejectsAnotherPeriod() throws Exception {
        String item=item(); String chosen=bom(item,1,"approved","2030-01-01","2030-01-31");
        bom(item,2,"approved","2030-02-01","2030-02-28");
        String id=id(call(post("/work-orders"),order(item,chosen,"2030-01-10T00:00:00Z")),"workOrderId");
        call(post("/work-orders/"+id+"/approve"),null).andExpect(status().isOk());
        var change=new LinkedHashMap<String,Object>();
        change.put("requestId",UUID.randomUUID());change.put("reason","Move plan");
        change.put("expectedPlannedStartAt","2030-01-10T00:00:00Z");change.put("expectedPlannedEndAt",null);
        change.put("plannedStartAt","2030-02-10T00:00:00Z");change.put("plannedEndAt",null);
        call(post("/work-orders/"+id+"/reschedules"),change).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message",containsString("bomId")));
        assertEquals(0,jdbc.queryForObject("select count(*) from work_order_reschedule where work_order_id=?",Integer.class,id));
        change.put("plannedStartAt","2030-01-20T00:00:00Z");
        call(post("/work-orders/"+id+"/reschedules"),change).andExpect(status().isOk());
        assertEquals(chosen,jdbc.queryForObject("select bom_id from work_order where work_order_id=?",String.class,id));
    }

    private Map<String,Object> order(String item,String bom,String start) {
        var body=new LinkedHashMap<String,Object>(); body.put("projectId",DEMO_PROJECT);body.put("workflowId",DEMO_WORKFLOW);
        body.put("workOrderTitle","Dated plan");body.put("targetItemId",item);body.put("targetQuantity",1);
        body.put("bomId",bom);body.put("plannedStartAt",start);return body;
    }
    private String item(){String id=UUID.randomUUID().toString();jdbc.update("insert into item(item_id,project_id,item_code,item_name,unit_id) values(?,?,?,?,?)",id,DEMO_PROJECT,"DATE-"+id.substring(0,8),"Dated product","unit_ea");return id;}
    private String bom(String item,int version,String state,String from,String to){
        String id=UUID.randomUUID().toString();
        jdbc.update("insert into bom_header(bom_id,project_id,target_item_id,bom_name,bom_version,base_quantity,base_unit,bom_status,approval_status,created_by,effective_from,effective_to) values(?,?,?,?,?,1,'ea',?,?,?,cast(? as date),cast(? as date))",id,DEMO_PROJECT,item,"Dated BOM",version,state,state,DEMO_OWNER,from,to);
        jdbc.update("insert into bom_line(bom_line_id,bom_id,child_item_id,quantity,unit) values(?,?,?,1,'ea')",UUID.randomUUID().toString(),id,item());return id;
    }
    private String id(ResultActions result,String field)throws Exception{return mapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data").path(field).asText();}
    private ResultActions call(MockHttpServletRequestBuilder request,Map<String,?> body)throws Exception{
        request.header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER));
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));return mvc.perform(request);
    }
}
