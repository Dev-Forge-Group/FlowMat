package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.batch.NotificationReminderBatchService;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Notifications people choose per project (docs/domain/notification.md NS1-NS9) against real Postgres. */
@AutoConfigureMockMvc
class NotificationSubscriptionIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotificationReminderBatchService reminders;

    @Test
    void eachPersonChoosesTheirOwnTopicsInAProject() throws Exception {
        String member = member();
        callAs(member, get("/notification-subscriptions").param("projectId", DEMO_PROJECT), null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[0].topic").value("stock_alert"))
            .andExpect(jsonPath("$.data[0].subscribed").value(false))
            .andExpect(jsonPath("$.data[2].topic").value("work_order_due"))
            .andExpect(jsonPath("$.data[2].daysAhead").isEmpty());

        choose(member, "work_order_due", true, null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[2].subscribed").value(true))
            .andExpect(jsonPath("$.data[2].daysAhead").value(3));
        choose(member, "work_order_due", true, 7).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[2].daysAhead").value(7));
        choose(member, "stock_alert", true, null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].subscribed").value(true));
        // The owner's choices are their own.
        callAs(DEMO_OWNER, get("/notification-subscriptions").param("projectId", DEMO_PROJECT), null)
            .andExpect(jsonPath("$.data[2].daysAhead").value(org.hamcrest.Matchers.not(7)));
        choose(member, "stock_alert", false, null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].subscribed").value(false));

        choose(member, "everything", true, null).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Unknown topic 'everything'. Use stock_alert, ncr_check_overdue or work_order_due."));
        choose(member, "work_order_due", true, 31).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("daysAhead must be whole days from 0 to 30."));
        choose(member, "stock_alert", true, 2).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("daysAhead is only for work_order_due."));
        choose(member, "stock_alert", null, null).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("subscribed is required."));
        callAs(member, put("/notification-subscriptions"), "{\"projectId\":\"" + DEMO_PROJECT
            + "\",\"topic\":\"work_order_due\",\"subscribed\":true,\"daysAhead\":\"soon\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("daysAhead as whole days")));
        callAs(member, get("/notification-subscriptions"), null).andExpect(status().isBadRequest());
        callAs("subscription-outsider", get("/notification-subscriptions").param("projectId", DEMO_PROJECT), null)
            .andExpect(status().isForbidden());
        choose("subscription-outsider", "stock_alert", true, null).andExpect(status().isForbidden());
    }

    @Test
    void anOpenedStockAlertReachesThoseWhoChoseItAndTheOwnerOnce() throws Exception {
        String chose = member();
        String didNot = member();
        choose(chose, "stock_alert", true, null).andExpect(status().isOk());
        // The owner also choosing it is not told twice.
        choose(DEMO_OWNER, "stock_alert", true, null).andExpect(status().isOk());
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String item = data(callAs(DEMO_OWNER, post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", "SUB-" + tag,
            "itemName", "sub", "unitId", "unit_kg", "lotManageYn", "N")))).path("itemId").asText();
        String row = data(callAs(DEMO_OWNER, post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", item,
            "quantity", 20, "location", "SUB-WH-" + tag, "minThreshold", 10)))).path("inventoryId").asText();
        callAs(DEMO_OWNER, post("/inventory-transactions"), json(Map.of("inventoryId", row, "transactionType", "issue",
            "quantity", 15, "requestId", UUID.randomUUID().toString()))).andExpect(status().isOk());

        List<JsonNode> heard = titled(chose, "SUB-" + tag + " ");
        assertEquals(1, heard.size());
        assertEquals("Low stock: SUB-" + tag + " at SUB-WH-" + tag, heard.get(0).path("title").asText());
        assertEquals("stock_alert", heard.get(0).path("refType").asText());
        assertEquals(1, titled(DEMO_OWNER, "SUB-" + tag + " ").size());
        assertEquals(0, titled(didNot, "SUB-" + tag + " ").size());
        choose(DEMO_OWNER, "stock_alert", false, null).andExpect(status().isOk());
    }

    @Test
    void theSweepRemindsOfDueWorkOrdersAndOverdueChecksOnce() throws Exception {
        String chose = member();
        String didNot = member();
        choose(chose, "work_order_due", true, 2).andExpect(status().isOk());
        choose(chose, "ncr_check_overdue", true, null).andExpect(status().isOk());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        String near = approvedOrder("Near", now.plusHours(1), now.plusDays(1));
        String far = approvedOrder("Far", now.plusHours(1), now.plusDays(5));
        String nearNumber = data(callAs(DEMO_OWNER, get("/work-orders/" + near), null)).path("workOrderNumber").asText();
        // A closed NCR whose check was due two days ago (closing needs a cause, a disposition and a done action; set it here).
        String ncr = data(callAs(DEMO_OWNER, post("/nonconformities"), json(Map.of("projectId", DEMO_PROJECT, "title",
            "Dents " + UUID.randomUUID())))).path("nonconformityId").asText();
        String ncrNo = data(callAs(DEMO_OWNER, get("/nonconformities/" + ncr), null)).path("ncrNo").asText();
        LocalDate due = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(2);
        jdbc.update("update nonconformity set status = 'closed', closed_by = ?, closed_at = now(), verification_due_date = ? "
            + "where nonconformity_id = ?", DEMO_OWNER, java.sql.Date.valueOf(due), ncr);

        reminders.remind(now);
        List<JsonNode> aboutNear = about(chose, near);
        assertEquals(1, aboutNear.size());
        assertEquals("work_order_due", aboutNear.get(0).path("type").asText());
        LocalDate nearDay = now.plusDays(1).atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDate();
        assertEquals(nearNumber + " is due " + nearDay, aboutNear.get(0).path("title").asText());
        assertEquals("work_order", aboutNear.get(0).path("refType").asText());
        assertEquals(0, about(chose, far).size());
        List<JsonNode> aboutCheck = about(chose, ncr);
        assertEquals(1, aboutCheck.size());
        assertEquals("ncr_check_overdue", aboutCheck.get(0).path("type").asText());
        assertEquals("Effectiveness check of " + ncrNo + " is overdue", aboutCheck.get(0).path("title").asText());
        assertEquals("nonconformity", aboutCheck.get(0).path("refType").asText());
        assertEquals(0, about(didNot, near).size());

        // The next sweep finds them again and tells no one twice.
        reminders.remind(now.plusMinutes(5));
        assertEquals(1, about(chose, near).size());
        assertEquals(1, about(chose, ncr).size());

        // Someone who left the project hears nothing more.
        jdbc.update("update project_member set member_status = 'removed' where project_id = ? and user_id = ?", DEMO_PROJECT, chose);
        String later = approvedOrder("Later", now.plusHours(1), now.plusDays(1));
        reminders.remind(now.plusMinutes(10));
        jdbc.update("update project_member set member_status = 'active' where project_id = ? and user_id = ?", DEMO_PROJECT, chose);
        assertEquals(0, about(chose, later).size());

        callAs(DEMO_OWNER, post("/work-orders/" + near + "/cancel"), "{}").andExpect(status().isOk());
        callAs(DEMO_OWNER, post("/work-orders/" + far + "/cancel"), "{}").andExpect(status().isOk());
        callAs(DEMO_OWNER, post("/work-orders/" + later + "/cancel"), "{}").andExpect(status().isOk());
    }

    private String approvedOrder(String title, OffsetDateTime start, OffsetDateTime end) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", DEMO_PROJECT);
        body.put("workOrderTitle", title + " " + UUID.randomUUID().toString().substring(0, 8));
        body.put("workflowId", DEMO_WORKFLOW);
        body.put("targetQuantity", 10);
        body.put("plannedStartAt", start.toString());
        body.put("plannedEndAt", end.toString());
        String order = data(callAs(DEMO_OWNER, post("/work-orders"), json(body))).path("workOrderId").asText();
        callAs(DEMO_OWNER, post("/work-orders/" + order + "/approve"), null).andExpect(status().isOk());
        return order;
    }

    private ResultActions choose(String userId, String topic, Boolean subscribed, Integer daysAhead) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", DEMO_PROJECT);
        body.put("topic", topic);
        body.put("subscribed", subscribed);
        body.put("daysAhead", daysAhead);
        return callAs(userId, put("/notification-subscriptions"), json(body));
    }

    /** The user's notifications about {@code refId}. */
    private List<JsonNode> about(String userId, String refId) throws Exception {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode one : data(callAs(userId, get("/notifications"), null))) {
            if (refId.equals(one.path("refId").asText())) {
                found.add(one);
            }
        }
        return found;
    }

    private List<JsonNode> titled(String userId, String text) throws Exception {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode one : data(callAs(userId, get("/notifications"), null))) {
            if (one.path("title").asText().contains(text)) {
                found.add(one);
            }
        }
        return found;
    }

    private String member() {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), DEMO_PROJECT, id, "editor");
        return id;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private ResultActions callAs(String userId, MockHttpServletRequestBuilder request, String body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(userId)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
}
