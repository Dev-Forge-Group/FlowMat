package org.myweb.flowmat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ProductionRunEndTimeIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private String start() throws Exception {
        var result=mvc.perform(post("/production-runs/start").header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON).content("{\"projectId\":\""+DEMO_PROJECT+"\",\"workflowId\":\""+DEMO_WORKFLOW+"\",\"plannedOutputQty\":1}"))
            .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("productionRunId").asText();
    }
    @Test void finishRecordsServerUtcTimeAndRepeatedFinishCannotMoveIt() throws Exception {
        String id=start();
        OffsetDateTime before=OffsetDateTime.now();
        var result=mvc.perform(post("/production-runs/"+id+"/finish").header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON).content("{\"actualOutputQty\":1,\"actualEndAt\":\"2000-01-01T00:00:00Z\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.actualEndAt").isString()).andReturn();
        OffsetDateTime at=OffsetDateTime.parse(json.readTree(result.getResponse().getContentAsString()).path("data").path("actualEndAt").asText());
        assertFalse(at.isBefore(before));
        assertFalse(at.isAfter(OffsetDateTime.now()));
        assertEquals(0,at.getOffset().getTotalSeconds());
        mvc.perform(post("/production-runs/"+id+"/finish").header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER)).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        OffsetDateTime stored=jdbc.queryForObject("select actual_end_at from production_run where production_run_id=?",OffsetDateTime.class,id);
        assertEquals(at.toInstant(),stored.toInstant());
        mvc.perform(get("/production-runs/"+id+"/cost").header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.costBasis").value("HISTORICAL"));
    }
    @Test void unauthorizedFinishDoesNotCreateTimeAndLegacyFinishedRunsStayEstimated() throws Exception {
        String id=start();
        mvc.perform(post("/production-runs/"+id+"/finish").header("Authorization","Bearer "+jwt.generateAccessToken("end-outsider")).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        assertNull(jdbc.queryForObject("select actual_end_at from production_run where production_run_id=?",OffsetDateTime.class,id));
        jdbc.update("update production_run set run_status='finished',actual_end_at=null where production_run_id=?",id);
        mvc.perform(get("/production-runs/"+id+"/cost").header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER)))
            .andExpect(jsonPath("$.data.costBasis").value("ESTIMATED")).andExpect(jsonPath("$.data.costBasisAt").isEmpty());
    }
}
