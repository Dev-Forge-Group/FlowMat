package org.myweb.flowmat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class EquipmentSetupPreviewIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void previewExplainsTheSameSelectionUsedByPlanningWithoutSavingAnyData() throws Exception {
        String equipment=equipment(),from=item(),to=item();
        preview(equipment,from,to).andExpect(status().isOk()).andExpect(jsonPath("$.data.ruleType").value("NONE"))
            .andExpect(jsonPath("$.data.minutes").value(0)).andExpect(jsonPath("$.data.changeoverId").isEmpty());
        pair(equipment,Map.of("minutes",7));
        preview(equipment,from,to).andExpect(jsonPath("$.data.ruleType").value("DEFAULT")).andExpect(jsonPath("$.data.minutes").value(7));
        pair(equipment,Map.of("toItemId",to,"minutes",9));
        preview(equipment,from,to).andExpect(jsonPath("$.data.ruleType").value("TO_ITEM")).andExpect(jsonPath("$.data.minutes").value(9));
        pair(equipment,Map.of("fromItemId",from,"minutes",11));
        preview(equipment,from,to).andExpect(jsonPath("$.data.ruleType").value("FROM_ITEM")).andExpect(jsonPath("$.data.minutes").value(11));
        call(put("/items/"+from+"/setup-attributes"),Map.of("attributes",Map.of("color","red"),"expectedVersion",0)).andExpect(status().isOk());
        String id=UUID.randomUUID().toString();
        call(put("/equipments/"+equipment+"/setup-changeovers/"+id),Map.of("fromAttributes",Map.of("color","red"),"toAttributes",Map.of(),"priority",1,"minutes",30,"expectedVersion",0))
            .andExpect(status().isOk());
        preview(equipment,from,to).andExpect(jsonPath("$.data.ruleType").value("ATTRIBUTE_RULE"))
            .andExpect(jsonPath("$.data.minutes").value(30)).andExpect(jsonPath("$.data.changeoverId").value(id));
        pair(equipment,Map.of("fromItemId",from,"toItemId",to,"minutes",60));
        preview(equipment,from,to).andExpect(jsonPath("$.data.ruleType").value("EXACT_ITEM_PAIR")).andExpect(jsonPath("$.data.minutes").value(60));
        assertEquals(1,jdbc.queryForObject("select version from item_setup_attributes where item_id=?",Long.class,from));
        assertEquals(1,jdbc.queryForObject("select version from equipment_setup_changeover where changeover_id=?",Long.class,id));
    }

    @Test
    void sameItemNeedsNoSwitchExceptForAnExplicitSelfPair() throws Exception {
        String equipment=equipment(),item=item();
        pair(equipment,Map.of("minutes",7));
        preview(equipment,item,item).andExpect(status().isOk()).andExpect(jsonPath("$.data.ruleType").value("NONE"))
            .andExpect(jsonPath("$.data.minutes").value(0));
        pair(equipment,Map.of("fromItemId",item,"toItemId",item,"minutes",15));
        preview(equipment,item,item).andExpect(jsonPath("$.data.ruleType").value("EXACT_ITEM_PAIR")).andExpect(jsonPath("$.data.minutes").value(15));
    }

    @Test
    void viewerCanPreviewButForeignUnknownOrDeletedInputsCannotBeUsed() throws Exception {
        String equipment=equipment(),from=item(),to=item(),viewer=UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?",viewer,viewer+"@test.local",DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,'viewer')",UUID.randomUUID().toString(),DEMO_PROJECT,viewer);
        mvc.perform(request(equipment,from,to).header("Authorization","Bearer "+jwt.generateAccessToken(viewer))).andExpect(status().isOk());
        mvc.perform(request(equipment,from,to).header("Authorization","Bearer "+jwt.generateAccessToken("unrelated-user"))).andExpect(status().isForbidden());
        preview(equipment," ",to).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("fromItemId")));
        preview(equipment,from,"missing").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("toItemId")));
        jdbc.update("update item set project_id='foreign-project' where item_id=?",to);
        preview(equipment,from,to).andExpect(status().isBadRequest());
        jdbc.update("update item set project_id=?,deleted_yn='Y' where item_id=?",DEMO_PROJECT,to);
        preview(equipment,from,to).andExpect(status().isBadRequest());
        preview("missing",from,from).andExpect(status().isNotFound());
        call(delete("/equipments/"+equipment),null).andExpect(status().isOk());
        preview(equipment,from,from).andExpect(status().isNotFound());
    }

    private void pair(String equipment,Map<String,Object> fields)throws Exception {call(post("/equipments/"+equipment+"/changeovers"),fields).andExpect(status().isOk());}
    private MockHttpServletRequestBuilder request(String equipment,String from,String to){return get("/equipments/"+equipment+"/setup-preview").param("fromItemId",from).param("toItemId",to);}
    private ResultActions preview(String equipment,String from,String to)throws Exception{return call(request(equipment,from,to),null);}
    private String equipment()throws Exception{return data(call(post("/equipments"),Map.of("projectId",DEMO_PROJECT,"equipmentName","Preview press","equipmentType","machine"))).path("equipmentId").asText();}
    private String item()throws Exception{return data(call(post("/items"),Map.of("projectId",DEMO_PROJECT,"itemCode",UUID.randomUUID().toString(),"itemName","Preview product","itemType","product","unitId","unit_ea"))).path("itemId").asText();}
    private JsonNode data(ResultActions result)throws Exception{return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
    private ResultActions call(MockHttpServletRequestBuilder request,Map<String,?> body)throws Exception{if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));return mvc.perform(request.header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER)));}
}
