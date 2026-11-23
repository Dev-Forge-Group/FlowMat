package org.myweb.flowmat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ProjectCalendarBoundaryIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtProvider jwt;
    @Autowired ProjectCalendarQuery dates;
    @Autowired org.myweb.flowmat.domain.production.application.UsableStock usable;
    @MockitoBean Clock projectClock;
    @BeforeEach void clock() { when(projectClock.instant()).thenReturn(Instant.parse("2026-10-08T15:30:00Z")); }
    private ResultActions call(MockHttpServletRequestBuilder req, Map<String,?> body) throws Exception {
        if(body!=null)req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(req.header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER)));
    }
    private String create(MockHttpServletRequestBuilder req,Map<String,?> body,String field) throws Exception {
        return json.readTree(call(req,body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path(field).asText();
    }
    private String project() throws Exception { return create(post("/projects"),Map.of("projectName","Calendar "+UUID.randomUUID(),"ownerId",DEMO_OWNER),"projectId"); }
    @Test void eachProjectHasItsOwnBusinessDateAndEquipmentCalendarZone() throws Exception {
        String seoul=project(),utc=project();
        call(put("/projects/"+utc+"/time-zone"),Map.of("timeZone","UTC","expectedVersion",0)).andExpect(status().isOk());
        assertEquals("2026-10-09",dates.today(seoul).toString());
        assertEquals("2026-10-08",dates.today(utc).toString());
        String equipment=create(post("/equipments"),Map.of("projectId",utc,"equipmentName","UTC machine","equipmentType","machine"),"equipmentId");
        call(get("/equipments/"+equipment+"/schedule"),null).andExpect(status().isOk()).andExpect(jsonPath("$.data.timeZone").value("UTC"));
        call(put("/projects/"+utc+"/time-zone"),Map.of("timeZone","America/New_York","expectedVersion",1)).andExpect(status().isOk());
        call(get("/equipments/"+equipment+"/schedule"),null).andExpect(jsonPath("$.data.timeZone").value("America/New_York"));
        assertEquals("2026-03-07",dates.date(utc,Instant.parse("2026-03-08T04:30:00Z")).toString());
    }
    @Test void lotExpiryFollowsProjectMidnightRatherThanMachineDate() throws Exception {
        String project=project();
        String item=create(post("/items"),Map.of("projectId",project,"itemCode",UUID.randomUUID().toString(),"itemName","Dated material","itemType","material","unitId","unit_kg","lotManageYn","Y"),"itemId");
        String lot=create(post("/lots"),Map.of("projectId",project,"itemId",item,"lotNo",UUID.randomUUID().toString(),"expiryDate","2026-10-08"),"lotId");
        call(get("/lots/"+lot),null).andExpect(status().isOk()).andExpect(jsonPath("$.data.expired").value(true));
        create(post("/inventories"),Map.of("projectId",project,"itemId",item,"lotId",lot,"quantity",10),"inventoryId");
        assertEquals(0,usable.byItem(project,java.util.List.of(item)).getOrDefault(item,java.math.BigDecimal.ZERO).signum());
        call(put("/projects/"+project+"/time-zone"),Map.of("timeZone","UTC","expectedVersion",0)).andExpect(status().isOk());
        call(get("/lots/"+lot),null).andExpect(jsonPath("$.data.expired").value(false));
        assertEquals(0,new java.math.BigDecimal("10").compareTo(usable.byItem(project,java.util.List.of(item)).get(item)));
    }
}
