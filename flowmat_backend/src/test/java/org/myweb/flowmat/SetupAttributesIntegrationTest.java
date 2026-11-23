package org.myweb.flowmat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class SetupAttributesIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtProvider jwt;
    @Autowired CatalogQuery catalog;
    @Autowired JdbcTemplate jdbc;

    @Test
    void itemHasSeveralSetupDimensionsWithoutOverwritingDetailsAndReplaysOnlyItsAuthorsLastSave() throws Exception {
        String item = item();
        assertTrue(data(call(get(attrs(item)), null)).path("attributes").isEmpty());
        call(put(attrs(item)), json.writeValueAsString(Map.of("attributes", Map.of(" color ", " red ", "mold", "M1"), "expectedVersion", 0)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.attributes.color").value("red"))
            .andExpect(jsonPath("$.data.attributes.mold").value("M1")).andExpect(jsonPath("$.data.version").value(1));
        call(put(attrs(item)), "{\"attributes\":{\"mold\":\"M1\",\"color\":\"red\"},\"expectedVersion\":0}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        call(put("/items/"+item), "{\"details\":{\"spec\":\"Master detail\"}}").andExpect(status().isOk());
        call(get(attrs(item)), null).andExpect(jsonPath("$.data.attributes.mold").value("M1"));
        call(put(attrs(item)), "{\"attributes\":{\"color\":\"blue\"},\"expectedVersion\":0}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(containsString("expectedVersion")));
        call(put(attrs(item)), "{\"attributes\":{},\"expectedVersion\":1}").andExpect(status().isOk());
        assertTrue(data(call(get(attrs(item)), null)).path("attributes").isEmpty());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("update item_setup_attributes set attributes='[1]'::jsonb where item_id=?",item));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("update item_setup_attributes set attributes='{\"color\":1}'::jsonb where item_id=?",item));
    }

    @Test
    void attributesRequireBoundedStringMapsAndExplicitLoadedVersions() throws Exception {
        String item = item();
        for (String attributes : List.of("null","[]","{\"color\":1}","{\"color\":null}","{\"color\":\" \"}",
            "{\" color\":\"red\",\"color\":\"blue\"}","{\"__proto__\":\"bad\"}","{\"constructor\":\"bad\"}"))
            call(put(attrs(item)), "{\"attributes\":"+attributes+",\"expectedVersion\":0}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("attributes")));
        call(put(attrs(item)), "{\"attributes\":{},\"expectedVersion\":0.5}").andExpect(status().isBadRequest());
        call(put(attrs(item)), "{\"attributes\":{}}").andExpect(status().isBadRequest());
        callAs("unrelated-user",get(attrs(item)),null).andExpect(status().isForbidden());
        callAs("unrelated-user",put(attrs(item)),"{\"attributes\":{},\"expectedVersion\":0}").andExpect(status().isForbidden());
    }

    @Test
    void exactItemPairThenConfiguredAttributePriorityThenLegacyFallbackDecideChangeover() throws Exception {
        String equipment = equipment(), from = item(), to = item(), other = item();
        setAttrs(from,Map.of("color","red","mold","M1"),0);
        setAttrs(to,Map.of("color","blue","mold","M2"),0);
        call(post("/equipments/"+equipment+"/changeovers"),"{\"minutes\":7}").andExpect(status().isOk());
        String specific = UUID.randomUUID().toString();
        rule(equipment,specific,Map.of("color","red","mold","M1"),Map.of("color","blue","mold","M2"),20,37,0).andExpect(status().isOk());
        assertEquals(37,catalog.changeoverMinutes(equipment,from,to).orElseThrow());
        setAttrs(to,Map.of("color","blue","mold","M3"),1);
        assertEquals(7,catalog.changeoverMinutes(equipment,from,to).orElseThrow());
        setAttrs(to,Map.of("color","blue"),2);
        assertEquals(7,catalog.changeoverMinutes(equipment,from,to).orElseThrow());
        setAttrs(to,Map.of("color","blue","mold","M2"),3);
        String first = UUID.randomUUID().toString();
        rule(equipment,first,Map.of("color","red"),Map.of("color","blue"),10,40,0).andExpect(status().isOk());
        assertEquals(40,catalog.changeoverMinutes(equipment,from,to).orElseThrow());
        call(post("/equipments/"+equipment+"/changeovers"),json.writeValueAsString(Map.of("fromItemId",from,"toItemId",to,"minutes",90))).andExpect(status().isOk());
        assertEquals(90,catalog.changeoverMinutes(equipment,from,to).orElseThrow());
        assertEquals(7,catalog.changeoverMinutes(equipment,from,other).orElseThrow());
        assertTrue(catalog.changeoverMinutes(equipment,from,from).isEmpty());
        // A missing required dimension is not a match; retire the generic rule and try a changed mold.
        call(delete(rules(equipment)+"/"+first).param("expectedVersion","1"),null).andExpect(status().isOk());
        setAttrs(to,Map.of("color","blue","mold","M3"),4);
        // Exact item pair remains authoritative regardless of attributes.
        assertEquals(90,catalog.changeoverMinutes(equipment,from,to).orElseThrow());
        assertEquals(7,catalog.changeoverMinutes(equipment,other,to).orElseThrow());
    }

    @Test
    void ruleIdentityVersionsUniquePriorityAndSoftDeletionProtectRetriesAndEdits() throws Exception {
        String equipment=equipment(),id=UUID.randomUUID().toString();
        rule(equipment,id,Map.of("color","red"),Map.of("color","blue"),1,30,0).andExpect(status().isOk());
        rule(equipment,id,Map.of("color","red"),Map.of("color","blue"),1,30,0).andExpect(status().isOk());
        assertEquals(1,data(call(get(rules(equipment)),null)).size());
        rule(equipment,UUID.randomUUID().toString(),Map.of("mold","M1"),Map.of("mold","M2"),1,60,0)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(containsString("priority")));
        rule(equipment,id,Map.of("color","red"),Map.of("color","blue"),1,45,1).andExpect(status().isOk());
        rule(equipment,id,Map.of("color","red"),Map.of("color","blue"),1,90,1).andExpect(status().isConflict());
        call(delete(rules(equipment)+"/"+id).param("expectedVersion","1"),null).andExpect(status().isConflict());
        call(delete(rules(equipment)+"/"+id).param("expectedVersion","2"),null).andExpect(status().isOk());
        rule(equipment,id,Map.of("color","red"),Map.of("color","blue"),1,30,0).andExpect(status().isConflict());
        assertEquals(0,data(call(get(rules(equipment)),null)).size());
        rule(equipment,UUID.randomUUID().toString(),Map.of(),Map.of(),1,30,0).andExpect(status().isBadRequest());
        callAs("unrelated-user",get(rules(equipment)),null).andExpect(status().isForbidden());
        call(put(rules(equipment)+"/not-a-uuid"),"{}").andExpect(status().isBadRequest());
    }

    @Test
    @Timeout(20)
    void concurrentCreatorsCannotOccupyTheSamePriority() throws Exception {
        String equipment=equipment();var gate=new CyclicBarrier(3);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var a=workers.submit(()->{gate.await(10,TimeUnit.SECONDS);return rule(equipment,UUID.randomUUID().toString(),Map.of("color","red"),Map.of("color","blue"),1,30,0).andReturn().getResponse().getStatus();});
            var b=workers.submit(()->{gate.await(10,TimeUnit.SECONDS);return rule(equipment,UUID.randomUUID().toString(),Map.of("mold","M1"),Map.of("mold","M2"),1,60,0).andReturn().getResponse().getStatus();});
            gate.await(10,TimeUnit.SECONDS);
            assertEquals(List.of(200,409),java.util.stream.Stream.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)).sorted().toList());
        }
        assertEquals(1,data(call(get(rules(equipment)),null)).size());
    }

    @Test
    @Timeout(20)
    void concurrentAttributeEditsDoNotLoseAnotherDimension() throws Exception {
        String item=item();var gate=new CyclicBarrier(3);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var a=workers.submit(()->{gate.await(10,TimeUnit.SECONDS);return call(put(attrs(item)),"{\"attributes\":{\"color\":\"red\"},\"expectedVersion\":0}").andReturn().getResponse().getStatus();});
            var b=workers.submit(()->{gate.await(10,TimeUnit.SECONDS);return call(put(attrs(item)),"{\"attributes\":{\"mold\":\"M1\"},\"expectedVersion\":0}").andReturn().getResponse().getStatus();});
            gate.await(10,TimeUnit.SECONDS);
            assertEquals(List.of(200,409),java.util.stream.Stream.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)).sorted().toList());
        }
        call(get(attrs(item)),null).andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    void viewersReadEditorsWriteAndAnotherAuthorCannotReplayOrSpoofTheSavedVersion() throws Exception {
        String item=item(),equipment=equipment(),id=UUID.randomUUID().toString();
        String viewer=user("viewer"),editor=user("editor");
        callAs(viewer,get(attrs(item)),null).andExpect(status().isOk());
        callAs(viewer,get(rules(equipment)),null).andExpect(status().isOk());
        String body=json.writeValueAsString(Map.of("attributes",Map.of("color","red"),"expectedVersion",0,"updatedBy","spoofed"));
        callAs(viewer,put(attrs(item)),body).andExpect(status().isForbidden());
        callAs(editor,put(attrs(item)),body).andExpect(status().isOk()).andExpect(jsonPath("$.data.updatedBy").value(editor));
        call(put(attrs(item)),body).andExpect(status().isConflict());
        String command=json.writeValueAsString(Map.of("fromAttributes",Map.of("color","red"),"toAttributes",Map.of(),"priority",1,"minutes",30,"expectedVersion",0));
        callAs(viewer,put(rules(equipment)+"/"+id),command).andExpect(status().isForbidden());
        callAs(editor,put(rules(equipment)+"/"+id),command).andExpect(status().isOk());
        call(put(rules(equipment)+"/"+id),command).andExpect(status().isConflict());
        callAs(viewer,delete(rules(equipment)+"/"+id).param("expectedVersion","1"),null).andExpect(status().isForbidden());
    }

    @Test
    void openPredicateSidesRequireLiveItemsAndUseAttributesBeforeLegacyItemWildcards() throws Exception {
        String equipment=equipment(),from=item(),to=item();
        setAttrs(from,Map.of("color","red"),0);
        call(post("/equipments/"+equipment+"/changeovers"),json.writeValueAsString(Map.of("fromItemId",from,"minutes",7))).andExpect(status().isOk());
        rule(equipment,UUID.randomUUID().toString(),Map.of("color","red"),Map.of(),1,30,0).andExpect(status().isOk());
        assertEquals(30,catalog.changeoverMinutes(equipment,from,to).orElseThrow());
        // Unknown and foreign items cannot satisfy an empty side of an attribute predicate.
        assertEquals(7,catalog.changeoverMinutes(equipment,from,"unknown-item").orElseThrow());
        jdbc.update("update item set project_id=? where item_id=?", "prj_demo_empty",to);
        assertEquals(7,catalog.changeoverMinutes(equipment,from,to).orElseThrow());
        jdbc.update("update item set project_id=? where item_id=?", DEMO_PROJECT,to);
        call(delete("/items/"+to),null).andExpect(status().isOk());
        assertEquals(7,catalog.changeoverMinutes(equipment,from,to).orElseThrow());
        call(get(attrs(to)),null).andExpect(status().isNotFound());
        call(put(attrs(to)),"{\"attributes\":{},\"expectedVersion\":0}").andExpect(status().isNotFound());
        call(delete("/equipments/"+equipment),null).andExpect(status().isOk());
        call(get(rules(equipment)),null).andExpect(status().isNotFound());
        rule(equipment,UUID.randomUUID().toString(),Map.of("color","red"),Map.of(),2,30,0).andExpect(status().isNotFound());
    }

    @Test
    void boundedMapsRuleMetadataMalformedJsonAndVersionLimitsFailWithoutOverwritingSavedValues() throws Exception {
        String item=item(),equipment=equipment(),id=UUID.randomUUID().toString();
        for(var values:List.of(Map.of("x".repeat(51),"v"),Map.of("x","v".repeat(101)),Map.of("co\nlor","v"),Map.of("x","v\u007f")))
            call(put(attrs(item)),json.writeValueAsString(Map.of("attributes",values,"expectedVersion",0))).andExpect(status().isBadRequest());
        var tooMany=new java.util.TreeMap<String,String>();for(int i=0;i<21;i++)tooMany.put("a"+i,"v");
        call(put(attrs(item)),json.writeValueAsString(Map.of("attributes",tooMany,"expectedVersion",0))).andExpect(status().isBadRequest());
        for(int[] invalid:List.of(new int[]{0,30},new int[]{100001,30},new int[]{1,0},new int[]{1,10081}))
            rule(equipment,id,Map.of("color","red"),Map.of(),invalid[0],invalid[1],0).andExpect(status().isBadRequest());
        call(put(attrs(item)),"{").andExpect(status().isBadRequest());
        call(put(rules(equipment)+"/"+id),"{").andExpect(status().isBadRequest());
        call(delete(rules(equipment)+"/"+id).param("expectedVersion","-1"),null).andExpect(status().isBadRequest());
        call(delete(rules(equipment)+"/"+id).param("expectedVersion","999999999999999999999"),null).andExpect(status().isBadRequest());
        setAttrs(item,Map.of("color","red"),0);
        rule(equipment,id,Map.of("color","red"),Map.of(),1,30,0).andExpect(status().isOk());
        jdbc.update("update item_setup_attributes set version=? where item_id=?",Long.MAX_VALUE,item);
        jdbc.update("update equipment_setup_changeover set version=? where changeover_id=?",Long.MAX_VALUE,id);
        call(put(attrs(item)),json.writeValueAsString(Map.of("attributes",Map.of("color","blue"),"expectedVersion",Long.MAX_VALUE))).andExpect(status().isConflict());
        rule(equipment,id,Map.of("color","red"),Map.of(),1,45,Long.MAX_VALUE).andExpect(status().isConflict());
        call(get(attrs(item)),null).andExpect(jsonPath("$.data.attributes.color").value("red"));
        call(get(rules(equipment)),null).andExpect(jsonPath("$.data[0].minutes").value(30));
    }

    @Test
    void unstorableUnicodeAndNulAreRejectedBeforeAnySetupDataIsWritten() throws Exception {
        String item=item(),equipment=equipment(),id=UUID.randomUUID().toString();
        for(String attributes:List.of("{\"color\":\"\\ud800\"}","{\"\\udfff\":\"red\"}"))
            call(put(attrs(item)),"{\"attributes\":"+attributes+",\"expectedVersion\":0}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("attributes")));
        for(String note:List.of("\\u0000","\\ud800"))
            call(put(rules(equipment)+"/"+id),"{\"fromAttributes\":{\"color\":\"red\"},\"toAttributes\":{},\"priority\":1,\"minutes\":30,\"expectedVersion\":0,\"note\":\""+note+"\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("note")));
        call(get(attrs(item)),null).andExpect(jsonPath("$.data.version").value(0));
        assertTrue(data(call(get(rules(equipment)),null)).isEmpty());
        setAttrs(item,Map.of("색상","빨강 😀"),0);
        call(get(attrs(item)),null).andExpect(jsonPath("$.data.attributes.색상").value("빨강 😀"));
        call(put(rules(equipment)+"/"+id),json.writeValueAsString(Map.of("fromAttributes",Map.of("색상","빨강 😀"),"toAttributes",Map.of(),"priority",1,"minutes",30,"expectedVersion",0,"note","청소\n준비 😀")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.note").value("청소\n준비 😀"));
    }

    private String user(String role) {
        String id="setup-"+UUID.randomUUID();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?",id,id+"@test.local",DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",UUID.randomUUID().toString(),DEMO_PROJECT,id,role);
        return id;
    }

    private void setAttrs(String item,Map<String,String> values,long version) throws Exception {
        call(put(attrs(item)),json.writeValueAsString(Map.of("attributes",values,"expectedVersion",version))).andExpect(status().isOk());
    }
    private ResultActions rule(String equipment,String id,Map<String,String> from,Map<String,String> to,int priority,int minutes,long version) throws Exception {
        return call(put(rules(equipment)+"/"+id),json.writeValueAsString(Map.of("fromAttributes",from,"toAttributes",to,"priority",priority,"minutes",minutes,"expectedVersion",version)));
    }
    private String attrs(String item){return "/items/"+item+"/setup-attributes";}
    private String rules(String equipment){return "/equipments/"+equipment+"/setup-changeovers";}
    private String equipment() throws Exception {return data(call(post("/equipments"),json.writeValueAsString(Map.of("projectId",DEMO_PROJECT,"equipmentName","Setup press","equipmentType","machine")))).path("equipmentId").asText();}
    private String item() throws Exception {return data(call(post("/items"),json.writeValueAsString(Map.of("projectId",DEMO_PROJECT,"itemCode",UUID.randomUUID().toString(),"itemName","Setup material","itemType","product","unitId","unit_ea")))).path("itemId").asText();}
    private JsonNode data(ResultActions result)throws Exception{return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
    private ResultActions call(MockHttpServletRequestBuilder request,String body)throws Exception{return callAs(DEMO_OWNER,request,body);}
    private ResultActions callAs(String actor,MockHttpServletRequestBuilder request,String body)throws Exception{if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(body);return mvc.perform(request.header("Authorization","Bearer "+jwt.generateAccessToken(actor)));}
}
