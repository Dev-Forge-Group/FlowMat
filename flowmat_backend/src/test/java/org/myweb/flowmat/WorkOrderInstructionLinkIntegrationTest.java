package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** A work order's link to its written instruction (V1 work_order.pdf_url), against real Postgres. */
@AutoConfigureMockMvc
class WorkOrderInstructionLinkIntegrationTest extends IntegrationTestSupport {

    private static final String LINK = "https://docs.example.com/wi-12.pdf";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void onlyAnHttpLinkIsKeptAndBlankClearsIt() throws Exception {
        String title = "WI " + UUID.randomUUID().toString().substring(0, 8);
        String orderId = objectMapper.readTree(send(post("/work-orders"), Map.of("projectId", DEMO_PROJECT, "workOrderTitle", title,
                "instructionUrl", "  " + LINK + " "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.instructionUrl").value(LINK))
                .andReturn().getResponse().getContentAsString())
            .path("data").path("workOrderId").asText();
        mockMvc.perform(auth(get("/work-orders/" + orderId))).andExpect(jsonPath("$.data.instructionUrl").value(LINK));

        // Script, data and relative addresses would run or resolve inside the app when opened: refused.
        for (String bad : new String[] {"javascript:alert(1)", "data:text/html,hi", "/files/wi.pdf", "ftp://files.example.com/wi.pdf",
            "https://" + "x".repeat(250) + ".com"}) {
            send(put("/work-orders/" + orderId), Map.of("workOrderTitle", title, "instructionUrl", bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Instruction link")));
        }
        send(post("/work-orders"), Map.of("projectId", DEMO_PROJECT, "workOrderTitle", title, "instructionUrl", "javascript:alert(1)"))
            .andExpect(status().isBadRequest());

        // The update replaces the editable fields: leaving the link out clears it, like the instruction text.
        Map<String, Object> cleared = new HashMap<>(Map.of("workOrderTitle", title));
        send(put("/work-orders/" + orderId), cleared).andExpect(jsonPath("$.data.instructionUrl").value(nullValue()));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return mockMvc.perform(auth(request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))));
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER));
    }
}
