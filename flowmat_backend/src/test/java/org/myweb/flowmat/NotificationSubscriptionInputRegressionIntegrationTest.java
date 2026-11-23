package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** NS2 rejects partial days and non-boolean choices before changing the current person's subscription. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class NotificationSubscriptionInputRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtProvider jwt;

    @ParameterizedTest
    @CsvSource({"daysAhead,0.5", "daysAhead,3.5", "daysAhead,30.5", "subscribed,1", "subscribed,0"})
    void malformedScalarInputsAreRefusedWithoutChangingTheChoices(String field, String literal) throws Exception {
        String project = project();
        String bearer = "Bearer " + jwt.generateAccessToken(DEMO_OWNER);
        JsonNode before = json.readTree(mvc.perform(get("/notification-subscriptions").param("projectId", project)
            .header("Authorization", bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        String body = "{\"projectId\":\"" + project + "\",\"topic\":\"work_order_due\",\"subscribed\":"
            + (field.equals("subscribed") ? literal : "true") + ",\"daysAhead\":"
            + (field.equals("daysAhead") ? literal : "3") + "}";
        mvc.perform(put("/notification-subscriptions").header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        JsonNode after = json.readTree(mvc.perform(get("/notification-subscriptions").param("projectId", project)
            .header("Authorization", bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(before, after);
        assertEquals(0, jdbc.queryForObject("select count(*) from notification_subscription where project_id=?", Integer.class, project));
    }

    @ParameterizedTest
    @CsvSource({"0,0", "30,30", "null,3", "3.0,3"})
    void validWholeDaysAndTheDefaultAreStillAccepted(String literal, int expected) throws Exception {
        String project = project();
        String bearer = "Bearer " + jwt.generateAccessToken(DEMO_OWNER);
        String body = "{\"projectId\":\"" + project
            + "\",\"topic\":\"work_order_due\",\"subscribed\":true,\"daysAhead\":" + literal + "}";
        JsonNode response = json.readTree(mvc.perform(put("/notification-subscriptions").header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(expected, response.path(2).path("daysAhead").asInt());
        assertEquals(expected, jdbc.queryForObject("select days_ahead from notification_subscription where project_id=?", Integer.class, project));
    }

    private String project() {
        String project = UUID.randomUUID().toString();
        jdbc.update("insert into project(project_id,project_name,owner_id,organization_id) "
            + "select ?,?,owner_id,organization_id from project where project_id=?",
            project, "Subscription input fixture", DEMO_PROJECT);
        return project;
    }

}
