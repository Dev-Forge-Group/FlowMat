package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A waiting rule write must read the preceding committed rule instead of undoing deletion or overwriting history. */
@AutoConfigureMockMvc
@Timeout(45)
class EquipmentChangeoverConcurrencyIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @ParameterizedTest
    @CsvSource({"remove,update,404,30", "update,remove,200,90"})
    void aWaitingRuleWriteObservesCommittedDeletionAndMinutes(String held,String waiting,int expectedStatus,int expectedMinutes) throws Exception {
        String equipment=mapper.readTree(call(post("/equipments"),Map.of("projectId",DEMO_PROJECT,"equipmentCode",UUID.randomUUID().toString(),
            "equipmentName","Rule race","equipmentType","machine")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
            .path("data").path("equipmentId").asText();
        String base="/equipments/"+equipment+"/changeovers";
        String rule=mapper.readTree(call(post(base),Map.of("minutes",30)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
            .path("data").get(0).path("changeoverId").asText();
        String path=base+"/"+rule;var pool=Executors.newSingleThreadExecutor();
        try {
            var request=new TransactionTemplate(transactionManager).execute(transaction->{
                try {
                    action(path,held,90).andExpect(status().isOk());
                    var next=pool.submit(()->action(path,waiting,120).andReturn().getResponse().getStatus());
                    DatabaseContention.awaitWaitingOrDone(jdbc,next,jdbc.queryForObject("select pg_backend_pid()",Integer.class));
                    return next;
                }catch(Exception error){throw new IllegalStateException(error);}
            });
            assertEquals(expectedStatus,request.get(15,TimeUnit.SECONDS));
            assertEquals("Y",jdbc.queryForObject("select deleted_yn from equipment_changeover where changeover_id=?",String.class,rule));
            assertEquals(expectedMinutes,jdbc.queryForObject("select changeover_minutes from equipment_changeover where changeover_id=?",Integer.class,rule));
        }finally{pool.shutdownNow();}
    }
    private ResultActions action(String path,String action,int minutes)throws Exception{return call("remove".equals(action)?delete(path):put(path),"remove".equals(action)?null:Map.of("minutes",minutes));}
    private ResultActions call(MockHttpServletRequestBuilder request,Map<String,?> body)throws Exception{
        request.header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER));
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));return mvc.perform(request);
    }
}
