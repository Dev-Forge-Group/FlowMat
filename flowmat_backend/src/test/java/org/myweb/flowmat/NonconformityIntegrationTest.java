package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.domain.quality.domain.entity.Nonconformity;
import org.myweb.flowmat.domain.quality.repository.NonconformityRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Nonconformities and corrective actions (docs/domain/nonconformity.md) against real Postgres. */
@AutoConfigureMockMvc
class NonconformityIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;
    @Autowired private NonconformityRepository nonconformityRepository;

    @Test
    void aNonconformityClosesOnlyWithCauseDispositionAndItsActionsFinished() throws Exception {
        String item = item();
        String minor = id(defect(item, "Scratch", "minor"), "defectLogId");
        String major = id(defect(item, "Crack", "major"), "defectLogId");

        JsonNode ncr = data(call(post("/nonconformities"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"title\":\"Cracked housings\","
            + "\"defectLogIds\":[\"" + minor + "\",\"" + major + "\"]}")
            .andExpect(jsonPath("$.data.ncrNo").value(matchesPattern("NCR-\\d{4}")))
            .andExpect(jsonPath("$.data.severity").value("major"))
            .andExpect(jsonPath("$.data.itemId").value(item))
            .andExpect(jsonPath("$.data.status").value("open"))
            .andExpect(jsonPath("$.data.defects.length()").value(2))
            .andExpect(jsonPath("$.data.disposition").value("pending")));
        String ncrId = ncr.path("nonconformityId").asText();
        String path = "/nonconformities/" + ncrId;

        call(post("/nonconformities"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"title\":\"Again\",\"defectLogIds\":[\"" + major + "\"]}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("is already on " + ncr.path("ncrNo").asText())));

        call(post(path + "/close"), "{}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("record the root cause, decide the disposition, complete at least one action")));

        call(post(path + "/actions"), "{\"actionType\":\"corrective\",\"description\":\"Change the mould\",\"ownerId\":\"unrelated-user\"}")
            .andExpect(status().isBadRequest());
        call(post(path + "/actions"), "{\"actionType\":\"later\",\"description\":\"x\"}").andExpect(status().isBadRequest());
        JsonNode withAction = data(call(post(path + "/actions"), "{\"actionType\":\"corrective\",\"description\":\"Change the mould\","
            + "\"ownerId\":\"" + DEMO_OWNER + "\",\"dueDate\":\"2000-01-01\"}")
            .andExpect(jsonPath("$.data.actions[0].actionNo").value(1))
            .andExpect(jsonPath("$.data.actions[0].overdue").value(true))
            .andExpect(jsonPath("$.data.overdueActions").value(1)));
        String corrective = withAction.path("actions").get(0).path("correctiveActionId").asText();
        call(post(path + "/actions/" + corrective + "/complete"), "{\"note\":\" \"}").andExpect(status().isBadRequest());
        call(post(path + "/actions/" + corrective + "/complete"), "{\"note\":\"New mould since lot 42\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.actions[0].status").value("done"))
            .andExpect(jsonPath("$.data.actions[0].overdue").value(false))
            .andExpect(jsonPath("$.data.openActions").value(0));
        call(post(path + "/actions/" + corrective + "/cancel"), "{\"note\":\"x\"}").andExpect(status().isConflict());

        JsonNode withPreventive = data(call(post(path + "/actions"), "{\"actionType\":\"preventive\",\"description\":\"Check other moulds\"}"));
        String preventive = withPreventive.path("actions").get(1).path("correctiveActionId").asText();
        call(put(path), "{\"rootCause\":\"Worn mould\",\"disposition\":\"rework\"}")
            .andExpect(jsonPath("$.data.rootCause").value("Worn mould"));
        call(put(path), "{\"disposition\":\"burn\"}").andExpect(status().isBadRequest());
        call(post(path + "/close"), "{}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("finish or cancel 1 open action")));
        call(post(path + "/actions/" + preventive + "/cancel"), "{\"note\":\"Only one mould\"}").andExpect(status().isOk());

        call(post(path + "/close"), "{\"note\":\"Verified on the next run\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("closed"))
            .andExpect(jsonPath("$.data.closureNote").value("Verified on the next run"));
        call(put(path), "{\"title\":\"Too late\"}").andExpect(status().isConflict());
        call(post(path + "/actions"), "{\"actionType\":\"correction\",\"description\":\"x\"}").andExpect(status().isConflict());
        call(get("/nonconformities").param("projectId", DEMO_PROJECT).param("status", "closed"))
            .andExpect(jsonPath("$.data[?(@.nonconformityId == '" + ncrId + "')]").isNotEmpty());

        // Whether the actions worked is checked once, on the closed nonconformity (N12); it stays closed either way.
        call(get(path)).andExpect(jsonPath("$.data.verificationResult").doesNotExist());
        call(post(path + "/verify"), "{\"result\":\"maybe\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("result must be effective or not_effective."));
        call(post(path + "/verify"), "{\"result\":\"not_effective\",\"note\":\" \"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Say what still goes wrong when the actions did not work."));
        call(post(path + "/verify"), "{\"result\":\"Not_Effective\",\"note\":\"Cracks again on lot 57\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("closed"))
            .andExpect(jsonPath("$.data.verificationResult").value("not_effective"))
            .andExpect(jsonPath("$.data.verificationNote").value("Cracks again on lot 57"))
            .andExpect(jsonPath("$.data.verifiedBy").value(DEMO_OWNER))
            .andExpect(jsonPath("$.data.verifiedAt").isNotEmpty());
        call(post(path + "/verify"), "{\"result\":\"effective\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("was already checked by " + DEMO_OWNER)));
    }

    @Test
    void cancellingFreesTheDefectsAndOutsidersCannotRead() throws Exception {
        String item = item();
        String defect = id(defect(item, "Stain", "critical"), "defectLogId");
        String ncrId = id(call(post("/nonconformities"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"title\":\"Stains\","
            + "\"defectLogIds\":[\"" + defect + "\"]}").andExpect(jsonPath("$.data.severity").value("critical")), "nonconformityId");
        call(post("/nonconformities/" + ncrId + "/actions"), "{\"actionType\":\"correction\",\"description\":\"Clean\"}");

        call(post("/nonconformities/" + ncrId + "/cancel"), "{}").andExpect(status().isBadRequest());
        call(post("/nonconformities/" + ncrId + "/cancel"), "{\"note\":\"Raised twice\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("cancelled"))
            .andExpect(jsonPath("$.data.defects.length()").value(0))
            .andExpect(jsonPath("$.data.actions[0].status").value("cancelled"));
        call(post("/nonconformities"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"title\":\"Stains again\",\"severity\":\"minor\","
            + "\"defectLogIds\":[\"" + defect + "\"]}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.severity").value("minor"));

        callAs("unrelated-user", get("/nonconformities").param("projectId", DEMO_PROJECT), null).andExpect(status().isForbidden());
        callAs("unrelated-user", get("/nonconformities/" + ncrId), null).andExpect(status().isForbidden());

        // Only a closed nonconformity's actions are checked (N12).
        call(post("/nonconformities/" + ncrId + "/verify"), "{\"result\":\"effective\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("is cancelled; only a closed nonconformity")));
        callAs("unrelated-user", post("/nonconformities/" + ncrId + "/verify"), "{\"result\":\"effective\"}")
            .andExpect(status().isForbidden());
    }

    @Test
    void aClosedNonconformitysActionsAreDueToBeCheckedByADay() throws Exception {
        String item = item();
        String defect = id(defect(item, "Burr", "minor"), "defectLogId");
        String ncrId = id(call(post("/nonconformities"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"title\":\"Burrs\","
            + "\"defectLogIds\":[\"" + defect + "\"]}"), "nonconformityId");
        String path = "/nonconformities/" + ncrId;
        String action = data(call(post(path + "/actions"), "{\"actionType\":\"correction\",\"description\":\"Deburr\"}"))
            .path("actions").get(0).path("correctiveActionId").asText();
        call(post(path + "/actions/" + action + "/complete"), "{\"note\":\"Deburred\"}").andExpect(status().isOk());
        call(put(path), "{\"rootCause\":\"Blunt cutter\",\"disposition\":\"rework\"}").andExpect(status().isOk());

        // Only an open nonconformity is closed, and only a closed one has a check date (N13).
        call(put(path + "/verification-due"), "{\"verificationDueDate\":\"2999-12-31\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("is open; only a closed nonconformity")));
        call(post(path + "/close"), "{\"verificationDueDate\":\"soon\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("a date is not YYYY-MM-DD")));
        call(post(path + "/close"), "{\"verificationDueDate\":\"2000-01-01\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("The check date 2000-01-01 is before today")));
        call(get(path)).andExpect(jsonPath("$.data.status").value("open"));
        call(post(path + "/close"), "{\"note\":\"Done\",\"verificationDueDate\":\"2999-12-31\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("closed"))
            .andExpect(jsonPath("$.data.verificationDueDate").value("2999-12-31"))
            .andExpect(jsonPath("$.data.verificationOverdue").value(false));

        call(put(path + "/verification-due"), "{\"verificationDueDate\":\"2000-01-01\"}").andExpect(status().isBadRequest());
        call(put(path + "/verification-due"), "{\"verificationDueDate\":null}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.verificationDueDate").doesNotExist())
            .andExpect(jsonPath("$.data.verificationOverdue").value(false));
        callAs("unrelated-user", put(path + "/verification-due"), "{\"verificationDueDate\":\"2999-12-31\"}")
            .andExpect(status().isForbidden());

        // A day that passed unchecked is overdue; recording the check ends that and keeps the day.
        Nonconformity stored = nonconformityRepository.findById(ncrId).orElseThrow();
        stored.setVerificationDueDate(LocalDate.of(2000, 1, 1));
        nonconformityRepository.saveAndFlush(stored);
        call(get("/nonconformities").param("projectId", DEMO_PROJECT).param("status", "closed"))
            .andExpect(jsonPath("$.data[?(@.nonconformityId == '" + ncrId + "')].verificationOverdue").value(true));
        call(post(path + "/verify"), "{\"result\":\"effective\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.verificationDueDate").value("2000-01-01"))
            .andExpect(jsonPath("$.data.verificationOverdue").value(false));
        call(put(path + "/verification-due"), "{\"verificationDueDate\":\"2999-12-31\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("was already checked by " + DEMO_OWNER)));
    }

    private ResultActions defect(String item, String type, String severity) throws Exception {
        return call(post("/defects"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + item + "\",\"quantity\":2,"
            + "\"defectType\":\"" + type + "\",\"severity\":\"" + severity + "\"}");
    }

    private String item() {
        String id = "itm-ncr-" + suffix();
        Item item = new Item();
        item.setItemId(id);
        item.setProjectId(DEMO_PROJECT);
        item.setItemCode(id.toUpperCase());
        item.setItemName(id);
        item.setItemType("material");
        item.setResourceCategory("material");
        item.setUnitId("unit_kg");
        item.setItemStatus("active");
        item.setLotManageYn("N");
        item.setDeletedYn("N");
        itemRepository.save(item);
        return id;
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return callAs(DEMO_OWNER, request, null);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return callAs(DEMO_OWNER, request, body);
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

    private String id(ResultActions result, String field) throws Exception {
        return data(result).path(field).asText();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
