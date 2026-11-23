package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.project.domain.entity.ProjectMember;
import org.myweb.flowmat.domain.project.repository.ProjectMemberRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** LOT inspection hold, release and reopen (docs/domain/lot-release.md) against real Postgres. */
@AutoConfigureMockMvc
class LotInspectionReleaseIntegrationTest extends IntegrationTestSupport {

    private static final String EDITOR = "lot-release-editor";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ProjectMemberRepository projectMemberRepository;

    @Test
    void aLotOfAnItemThatNeedsItsChecksIsHeldUntilTheyPass() throws Exception {
        String tag = tag();
        String flour = item("REL-" + tag, "Y");
        JsonNode standard = data(call(post("/inspection-standards"), json(Map.of("projectId", DEMO_PROJECT, "itemId", flour,
            "inspectionType", "Moisture", "stage", "receipt", "standardMax", 12, "required", true))));
        String lot = data(call(post("/lots"), json(Map.of("projectId", DEMO_PROJECT, "itemId", flour, "lotNo", "REL-L1-" + tag))))
            .path("lotId").asText();
        call(get("/lots/" + lot)).andExpect(jsonPath("$.data.lotStatus").value("inspection_pending"));

        // Stock can be received but is held: it cannot be issued, and a plain unquarantine is refused (R2, R4).
        JsonNode row = data(call(post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", flour, "quantity", 10,
            "location", "REL-DOCK-" + tag, "lotId", lot))));
        String inventory = row.path("inventoryId").asText();
        Assertions.assertEquals("quarantined", row.path("inventoryStatus").asText());
        move(inventory, "issue", 1).andExpect(status().isConflict());
        move(inventory, "unquarantine", null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("waits for its receipt checks")));
        call(get("/lots/" + lot)).andExpect(jsonPath("$.data.lotStatus").value("inspection_pending"));

        // Release needs the required check recorded and passing (R3).
        call(post("/lots/" + lot + "/release"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("record Moisture")));
        inspect(lot, standard.path("standardId").asText(), 15);
        call(post("/lots/" + lot + "/release"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("Moisture failed")));
        inspect(lot, standard.path("standardId").asText(), 10);
        callAs("lot-release-outsider", post("/lots/" + lot + "/release")).andExpect(status().isForbidden());
        ensureMember(EDITOR, "editor");
        callAs(EDITOR, post("/lots/" + lot + "/release"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lotStatus").value("available"));
        call(get("/inventories/" + inventory)).andExpect(jsonPath("$.data.inventoryStatus").value("available"));
        move(inventory, "issue", 1).andExpect(status().isOk());
        call(post("/lots/" + lot + "/release"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("not waiting for its receipt checks")));
        call(get("/inventory-transactions").param("inventoryId", inventory))
            .andExpect(jsonPath("$.data[?(@.referenceType == 'lot_inspection_release')].transactionType")
                .value(org.hamcrest.Matchers.hasItem("unquarantine")));

        // An item that does not need its checks starts its LOTs available as before (compatible default).
        String salt = item("RELN-" + tag, null);
        call(post("/lots"), json(Map.of("projectId", DEMO_PROJECT, "itemId", salt, "lotNo", "RELN-L1-" + tag)))
            .andExpect(jsonPath("$.data.lotStatus").value("available"));
        call(get("/items/" + salt)).andExpect(jsonPath("$.data.lotReleaseRequiredYn").value("N"));
        call(put("/items/" + salt), json(Map.of("lotReleaseRequiredYn", "Y")))
            .andExpect(jsonPath("$.data.lotReleaseRequiredYn").value("Y"));
        call(post("/lots"), json(Map.of("projectId", DEMO_PROJECT, "itemId", salt, "lotNo", "RELN-L2-" + tag)))
            .andExpect(jsonPath("$.data.lotStatus").value("inspection_pending"));
    }

    @Test
    void aFailedCheckTurnsTheWaitIntoAQuarantineAndOwnersReopenClosedLots() throws Exception {
        String tag = tag();
        String sugar = item("RELQ-" + tag, "Y");
        String lot = data(call(post("/lots"), json(Map.of("projectId", DEMO_PROJECT, "itemId", sugar, "lotNo", "RELQ-L1-" + tag))))
            .path("lotId").asText();
        String inventory = data(call(post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", sugar, "quantity", 4,
            "location", "RELQ-DOCK-" + tag, "lotId", lot)))).path("inventoryId").asText();

        Map<String, Object> failing = new HashMap<>(Map.of("projectId", DEMO_PROJECT, "lotId", lot, "inspectionType", "Visual",
            "result", "fail", "quarantineLot", true));
        call(post("/quality-inspections"), json(failing)).andExpect(status().isOk());
        call(get("/lots/" + lot)).andExpect(jsonPath("$.data.lotStatus").value("quarantined"));
        // From here it is an ordinary quarantine: unquarantining it puts the LOT back as its stock says (R5).
        move(inventory, "unquarantine", null).andExpect(status().isOk());
        call(get("/lots/" + lot)).andExpect(jsonPath("$.data.lotStatus").value("available"));

        // Empty, closed, then reopened by the owner only. Its item needs the checks, so it waits for them again (R6).
        move(inventory, "issue", 4).andExpect(status().isOk());
        call(post("/lots/" + lot + "/reopen")).andExpect(status().isConflict());
        call(post("/lots/" + lot + "/close")).andExpect(status().isOk());
        ensureMember(EDITOR, "editor");
        callAs(EDITOR, post("/lots/" + lot + "/reopen")).andExpect(status().isForbidden());
        call(post("/lots/" + lot + "/reopen"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lotStatus").value("inspection_pending"));
        call(get("/inventories/" + inventory)).andExpect(jsonPath("$.data.inventoryStatus").value("quarantined"));
        move(inventory, "receipt", 2).andExpect(status().isOk());
        call(get("/lots/" + lot)).andExpect(jsonPath("$.data.lotStatus").value("inspection_pending"));
        // This item has no required check, so Release LOT clears it straight away, and says so (R7).
        call(post("/lots/" + lot + "/release"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lotStatus").value("available"))
            .andExpect(jsonPath("$.data.checkedStandards").value(0));
        // The LOT keeps when it was held on reopening and released (R8), and when it was closed and reopened (R9), newest
        // first; the refused reopenings left nothing.
        call(get("/lots/" + lot + "/release-history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.events.length()").value(4))
            .andExpect(jsonPath("$.data.events[0].action").value("released"))
            .andExpect(jsonPath("$.data.events[0].stockRows").value(1))
            .andExpect(jsonPath("$.data.events[1].action").value("held"))
            .andExpect(jsonPath("$.data.events[1].by").value(DEMO_OWNER))
            .andExpect(jsonPath("$.data.events[2].action").value("reopened"))
            .andExpect(jsonPath("$.data.events[2].status").value("inspection_pending"))
            .andExpect(jsonPath("$.data.events[2].stockRows").value(0))
            .andExpect(jsonPath("$.data.events[3].action").value("closed"))
            .andExpect(jsonPath("$.data.events[3].status").value("closed"))
            .andExpect(jsonPath("$.data.events[3].by").value(DEMO_OWNER));

        // A LOT of an item without the checks comes back as its stock says.
        String salt = item("RELR-" + tag, null);
        String plain = data(call(post("/lots"), json(Map.of("projectId", DEMO_PROJECT, "itemId", salt, "lotNo", "RELR-L1-" + tag))))
            .path("lotId").asText();
        String plainRow = data(call(post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", salt, "quantity", 3,
            "location", "RELR-DOCK-" + tag, "lotId", plain)))).path("inventoryId").asText();
        move(plainRow, "issue", 3).andExpect(status().isOk());
        call(post("/lots/" + plain + "/close")).andExpect(status().isOk());
        // Closing it again changes nothing and is not a second event (R9).
        call(post("/lots/" + plain + "/close")).andExpect(status().isOk());
        call(post("/lots/" + plain + "/reopen"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lotStatus").value("consumed"));
        // An empty LOT moved no ledger row, yet its closing and reopening are kept.
        call(get("/lots/" + plain + "/release-history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.events.length()").value(2))
            .andExpect(jsonPath("$.data.events[0].action").value("reopened"))
            .andExpect(jsonPath("$.data.events[0].status").value("consumed"))
            .andExpect(jsonPath("$.data.events[1].action").value("closed"))
            .andExpect(jsonPath("$.data.events[1].by").value(DEMO_OWNER));
    }

    private String item(String code, String releaseRequired) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("projectId", DEMO_PROJECT, "itemCode", code, "itemName", code.toLowerCase(),
            "itemType", "material", "unitId", "unit_kg", "lotManageYn", "Y"));
        if (releaseRequired != null) {
            body.put("lotReleaseRequiredYn", releaseRequired);
        }
        JsonNode created = data(call(post("/items"), json(body)));
        Assertions.assertEquals(releaseRequired == null ? "N" : releaseRequired, created.path("lotReleaseRequiredYn").asText());
        return created.path("itemId").asText();
    }

    private void inspect(String lotId, String standardId, int measured) throws Exception {
        call(post("/quality-inspections"), json(Map.of("projectId", DEMO_PROJECT, "lotId", lotId, "inspectionType", "Moisture",
            "measuredValue", measured, "standardId", standardId))).andExpect(status().isOk());
    }

    private ResultActions move(String inventoryId, String type, Integer quantity) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("inventoryId", inventoryId, "transactionType", type,
            "requestId", UUID.randomUUID().toString()));
        if (quantity != null) {
            body.put("quantity", quantity);
        }
        return call(post("/inventory-transactions"), json(body));
    }

    private void ensureMember(String userId, String role) {
        if (projectMemberRepository.existsByProjectIdAndUserIdAndMemberStatus(DEMO_PROJECT, userId, "active")) {
            return;
        }
        ProjectMember member = new ProjectMember();
        member.setProjectMemberId("pm-rel-" + UUID.randomUUID().toString().substring(0, 8));
        member.setProjectId(DEMO_PROJECT);
        member.setUserId(userId);
        member.setProjectRole(role);
        member.setMemberStatus("active");
        member.setInvitedBy(DEMO_OWNER);
        member.setJoinedAt(OffsetDateTime.now());
        projectMemberRepository.save(member);
    }

    private static String tag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return callAs(DEMO_OWNER, request);
    }

    private ResultActions callAs(String user, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(user)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
