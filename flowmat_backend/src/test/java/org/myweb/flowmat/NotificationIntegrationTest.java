package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Notifications of work given to someone (docs/domain/notification.md NT1-NT6) against real Postgres. */
@AutoConfigureMockMvc
class NotificationIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void theOwnerOfAnActionOrATaskIsToldUntilTheyLeaveTheProject() throws Exception {
        String member = member();
        String ncr = data(callAs(DEMO_OWNER, post("/nonconformities"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"title\":\"Dents "
            + suffix() + "\"}")).path("nonconformityId").asText();
        String ncrNo = data(callAs(DEMO_OWNER, get("/nonconformities/" + ncr), null)).path("ncrNo").asText();

        // Giving an action to someone else tells them; giving one to yourself tells no one (NT2).
        callAs(DEMO_OWNER, post("/nonconformities/" + ncr + "/actions"), "{\"actionType\":\"corrective\",\"description\":\"Change the die\","
            + "\"ownerId\":\"" + member + "\",\"dueDate\":\"2030-01-31\"}").andExpect(status().isOk());
        callAs(DEMO_OWNER, post("/nonconformities/" + ncr + "/actions"), "{\"actionType\":\"correction\",\"description\":\"Sort the lot\","
            + "\"ownerId\":\"" + DEMO_OWNER + "\"}").andExpect(status().isOk());
        JsonNode told = data(callAs(member, get("/notifications"), null));
        org.junit.jupiter.api.Assertions.assertEquals(1, told.size());
        callAs(member, get("/notifications"), null)
            .andExpect(jsonPath("$.data[0].type").value("ncr_action"))
            .andExpect(jsonPath("$.data[0].title").value("Action 1 of " + ncrNo + " is yours"))
            .andExpect(jsonPath("$.data[0].body").value("Change the die · due 2030-01-31"))
            .andExpect(jsonPath("$.data[0].projectId").value(DEMO_PROJECT))
            .andExpect(jsonPath("$.data[0].refType").value("nonconformity"))
            .andExpect(jsonPath("$.data[0].refId").value(ncr))
            .andExpect(jsonPath("$.data[0].read").value(false));
        callAs(DEMO_OWNER, get("/notifications"), null)
            .andExpect(jsonPath("$.data[?(@.refId == '" + ncr + "')]").isEmpty());

        // A warehouse task given to them tells them too, once (NT2).
        String tag = suffix().substring(0, 8).toUpperCase();
        String item = data(callAs(DEMO_OWNER, post("/items"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemCode\":\"NTF-" + tag
            + "\",\"itemName\":\"ntf\",\"unitId\":\"unit_ea\",\"lotManageYn\":\"N\"}")).path("itemId").asText();
        String record = data(callAs(DEMO_OWNER, post("/inventories"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + item
            + "\",\"quantity\":3,\"location\":\"DOCK-" + tag + "\"}")).path("inventoryId").asText();
        String task = data(callAs(DEMO_OWNER, post("/warehouse-tasks"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"inventoryId\":\""
            + record + "\",\"quantity\":3,\"toLocation\":\"SHELF-" + tag + "\"}")).path("taskId").asText();
        callAs(DEMO_OWNER, put("/warehouse-tasks/" + task + "/assignee"), "{\"assignedTo\":\"" + member + "\"}").andExpect(status().isOk());
        callAs(DEMO_OWNER, put("/warehouse-tasks/" + task + "/assignee"), "{\"assignedTo\":\"" + member + "\"}").andExpect(status().isOk());
        callAs(member, get("/notifications/unread-count"), null).andExpect(jsonPath("$.data.unread").value(2));
        callAs(member, get("/notifications"), null)
            .andExpect(jsonPath("$.data[0].type").value("warehouse_task"))
            .andExpect(jsonPath("$.data[0].refId").value(task))
            .andExpect(jsonPath("$.data[0].body").value(org.hamcrest.Matchers.endsWith("to SHELF-" + tag + ".")));

        // Reading one is the person's own business; then all at once (NT3).
        String first = data(callAs(member, get("/notifications"), null)).get(0).path("notificationId").asText();
        callAs(DEMO_OWNER, post("/notifications/" + first + "/read"), null).andExpect(status().isNotFound());
        callAs(member, post("/notifications/" + first + "/read"), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.read").value(true))
            .andExpect(jsonPath("$.data.readAt").isNotEmpty());
        callAs(member, get("/notifications").param("unreadOnly", "true"), null).andExpect(jsonPath("$.data.length()").value(1));
        callAs(member, post("/notifications/read-all"), null).andExpect(jsonPath("$.data.marked").value(1));
        callAs(member, get("/notifications/unread-count"), null).andExpect(jsonPath("$.data.unread").value(0));

        // Someone who left the project is no longer shown its notifications (NT4).
        jdbc.update("update project_member set member_status = 'removed' where project_id = ? and user_id = ?", DEMO_PROJECT, member);
        callAs(member, get("/notifications"), null).andExpect(jsonPath("$.data.length()").value(0));
        callAs(member, post("/notifications/" + first + "/read"), null).andExpect(status().isNotFound());
    }

    @Test
    void aWorkOrdersAssigneeIsToldWhenTheNameIsAPersonOfTheProject() throws Exception {
        String member = member();
        String order = data(callAs(DEMO_OWNER, post("/work-orders"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workOrderTitle\":\"Rolls "
            + suffix().substring(0, 6) + "\",\"assignedTo\":\"" + member + "\"}")).path("workOrderId").asText();
        JsonNode created = data(callAs(DEMO_OWNER, get("/work-orders/" + order), null));
        callAs(member, get("/notifications"), null)
            .andExpect(jsonPath("$.data[0].type").value("work_order"))
            .andExpect(jsonPath("$.data[0].title").value("Work order " + created.path("workOrderNumber").asText() + " is yours"))
            .andExpect(jsonPath("$.data[0].body").value(created.path("workOrderTitle").asText()))
            .andExpect(jsonPath("$.data[0].refType").value("work_order"))
            .andExpect(jsonPath("$.data[0].refId").value(order));

        // Saving the draft with the same person tells no one again; a crew name is free text and tells no one (NT2).
        String title = created.path("workOrderTitle").asText();
        callAs(DEMO_OWNER, put("/work-orders/" + order), "{\"workOrderTitle\":\"" + title + "\",\"assignedTo\":\"" + member + "\"}")
            .andExpect(status().isOk());
        callAs(DEMO_OWNER, put("/work-orders/" + order), "{\"workOrderTitle\":\"" + title + "\",\"assignedTo\":\"Night crew\"}")
            .andExpect(status().isOk());
        callAs(member, get("/notifications/unread-count"), null).andExpect(jsonPath("$.data.unread").value(1));
    }

    @Test
    void anAlertThatOpensTellsTheProjectOwnerOnce() throws Exception {
        String tag = suffix().substring(0, 8).toUpperCase();
        String item = data(callAs(DEMO_OWNER, post("/items"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemCode\":\"ALR-" + tag
            + "\",\"itemName\":\"alr\",\"unitId\":\"unit_kg\",\"lotManageYn\":\"N\"}")).path("itemId").asText();
        String row = data(callAs(DEMO_OWNER, post("/inventories"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + item
            + "\",\"quantity\":20,\"location\":\"WH-" + tag + "\",\"minThreshold\":10}")).path("inventoryId").asText();

        // Opening tells the owner, even when the owner moved the stock (NT7); getting worse while open tells no one again.
        move(row, "issue", "15");
        move(row, "issue", "5");
        java.util.List<JsonNode> told = alertsFor("ALR-" + tag);
        org.junit.jupiter.api.Assertions.assertEquals(1, told.size());
        org.junit.jupiter.api.Assertions.assertEquals("Low stock: ALR-" + tag + " at WH-" + tag, told.get(0).path("title").asText());
        org.junit.jupiter.api.Assertions.assertEquals("Available 5 is below the minimum 10.", told.get(0).path("body").asText());
        org.junit.jupiter.api.Assertions.assertEquals("stock_alert", told.get(0).path("refType").asText());

        // Closed by a receipt and opened again: a new alert, a new notification.
        move(row, "receipt", "30");
        move(row, "issue", "25");
        org.junit.jupiter.api.Assertions.assertEquals(2, alertsFor("ALR-" + tag).size());
    }

    private void move(String inventoryId, String type, String quantity) throws Exception {
        callAs(DEMO_OWNER, post("/inventory-transactions"), "{\"inventoryId\":\"" + inventoryId + "\",\"transactionType\":\"" + type
            + "\",\"quantity\":" + quantity + ",\"requestId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isOk());
    }

    /** The owner's stock alert notifications about one item code. */
    private java.util.List<JsonNode> alertsFor(String itemCode) throws Exception {
        java.util.List<JsonNode> found = new java.util.ArrayList<>();
        for (JsonNode one : data(callAs(DEMO_OWNER, get("/notifications"), null))) {
            if ("stock_alert".equals(one.path("type").asText()) && one.path("title").asText().contains(itemCode + " ")) {
                found.add(one);
            }
        }
        return found;
    }

    /** A new active member of the demo project. */
    private String member() {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), DEMO_PROJECT, id, "editor");
        return id;
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

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
