package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class WorkflowResourceVisibilityIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"port", "port_list", "connection", "connection_list"})
    void deletingAWorkflowHidesItsResourcesWithoutRemovingTheirHistory(String resource) throws Exception {
        Fixture fixture = fixture();
        read(resource, fixture).andExpect(status().isOk());
        call(delete("/workflows/" + fixture.workflow())).andExpect(status().isOk());
        read(resource, fixture).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT deleted_yn FROM process_io WHERE process_io_id = ?", String.class,
            fixture.port())).isEqualTo("N");
        assertThat(jdbc.queryForObject("SELECT deleted_yn FROM process_connection WHERE connection_id = ?", String.class,
            fixture.connection())).isEqualTo("N");
    }

    private ResultActions read(String resource, Fixture fixture) throws Exception {
        return switch (resource) {
            case "port" -> call(get("/process-ios/" + fixture.port()));
            case "port_list" -> call(get("/process-ios").param("processId", fixture.process()));
            case "connection" -> call(get("/process-connections/" + fixture.connection()));
            case "connection_list" -> call(get("/process-connections").param("workflowId", fixture.workflow()));
            default -> throw new IllegalArgumentException(resource);
        };
    }

    private Fixture fixture() throws Exception {
        String workflow = data(call(post("/workflows"), Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Deleted resource visibility " + UUID.randomUUID()))).path("workflowId").asText();
        String source = data(call(post("/processes"), Map.of("workflowId", workflow, "processName", "Source")))
            .path("processId").asText();
        String target = data(call(post("/processes"), Map.of("workflowId", workflow, "processName", "Target")))
            .path("processId").asText();
        String port = data(call(post("/process-ios"), Map.of("processId", source, "itemId", "itm_demo_mix_output",
            "direction", "output", "quantity", 1, "unit", "kg"))).path("processIoId").asText();
        String connection = data(call(post("/process-connections"), Map.of("workflowId", workflow,
            "fromProcessId", source, "toProcessId", target))).path("connectionId").asText();
        return new Fixture(workflow, source, port, connection);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private record Fixture(String workflow, String process, String port, String connection) {}
}
