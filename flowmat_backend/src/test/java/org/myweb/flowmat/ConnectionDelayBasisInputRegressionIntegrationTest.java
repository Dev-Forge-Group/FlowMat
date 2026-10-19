package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** GR-53 input and snapshot compatibility, using disposable Postgres and no business BOM fixtures. */
@AutoConfigureMockMvc
class ConnectionDelayBasisInputRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    static Stream<String> invalidBases() {
        return Stream.of("42", "[]", "{}", "true", "\"\"", "\"  \"", "\"" + "x".repeat(51) + "\"");
    }

    @ParameterizedTest
    @MethodSource("invalidBases")
    void createRejectsInvalidBasesWithoutSavingAConnection(String literal) throws Exception {
        Line line = new Line(false);
        var before = line.rows();
        mvc.perform(auth(post("/process-connections")).contentType(MediaType.APPLICATION_JSON).content(line.body(literal)))
            .andExpect(status().isBadRequest());
        assertEquals(before, line.rows());
    }

    @ParameterizedTest
    @MethodSource("invalidBases")
    void updateRejectsInvalidBasesWithoutChangingStoredConnectionOrPublishedRevision(String literal) throws Exception {
        Line line = new Line(true);
        var before = line.rows();
        JsonNode revision = data(get(line.revisionPath()), null);
        mvc.perform(auth(put("/process-connections/" + line.connection)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"delayBasis\":" + literal + "}")).andExpect(status().isBadRequest());
        assertEquals(before, line.rows());
        assertEquals(revision, data(get(line.revisionPath()), null));
    }

    @Test
    void explicitNullOnCreateKeepsTheExistingElapsedDefault() throws Exception {
        Line line = new Line(false);
        var response = mvc.perform(auth(post("/process-connections")).contentType(MediaType.APPLICATION_JSON)
            .content(line.body("null"))).andExpect(status().isOk()).andReturn().getResponse();
        assertEquals("elapsed", mapper.readTree(response.getContentAsString()).path("data").path("delayBasis").asText());
    }

    @Test
    void nullAndOmittedUpdateKeepWorkingAndDoNotRewriteTheOldPublishedRevision() throws Exception {
        Line line = new Line(true);
        JsonNode before = data(get(line.revisionPath()), null);
        assertEquals("working", before.path("snapshot").path("connections").get(0).path("delayBasis").asText());
        for (String body : List.of("{\"delayBasis\":null}", "{}")) {
            var response = mvc.perform(auth(put("/process-connections/" + line.connection)).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk()).andReturn().getResponse();
            assertEquals("working", mapper.readTree(response.getContentAsString()).path("data").path("delayBasis").asText());
        }
        assertEquals(before, data(get(line.revisionPath()), null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Working", " working ", "ELAPSED", " elapsed "})
    void acceptedSpellingIsCanonicalAndAnEditLeavesThePublishedSnapshotIntact(String supplied) throws Exception {
        Line line = new Line(false);
        String canonical = supplied.trim().toLowerCase(Locale.ROOT);
        JsonNode created = data(post("/process-connections"), Map.of("workflowId", line.workflow,
            "fromProcessId", line.source, "toProcessId", line.target, "delayBasis", supplied));
        String connection = created.path("connectionId").asText();
        assertEquals(canonical, created.path("delayBasis").asText());
        JsonNode revision = data(post("/workflows/" + line.workflow + "/revisions"), null);
        String revisionPath = "/workflows/" + line.workflow + "/revisions/" + revision.path("workflowRevisionId").asText();
        JsonNode snapshot = data(get(revisionPath), null);
        assertEquals(canonical, snapshot.path("snapshot").path("connections").get(0).path("delayBasis").asText());
        String replacement = canonical.equals("working") ? "elapsed" : "working";
        assertEquals(replacement, data(put("/process-connections/" + connection), Map.of("delayBasis", replacement))
            .path("delayBasis").asText());
        assertEquals(snapshot, data(get(revisionPath), null));
    }

    private final class Line {
        final String workflow;
        final String source;
        final String target;
        String connection;
        String revision;

        Line(boolean published) throws Exception {
            workflow = data(post("/workflows"), Map.of("projectId", DEMO_PROJECT, "workflowName", "Delay input " + UUID.randomUUID()))
                .path("workflowId").asText();
            source = node("From");
            target = node("To");
            if (published) {
                connection = data(post("/process-connections"), Map.of("workflowId", workflow, "fromProcessId", source,
                    "toProcessId", target, "delayBasis", "working")).path("connectionId").asText();
                revision = data(post("/workflows/" + workflow + "/revisions"), null).path("workflowRevisionId").asText();
            }
        }

        String node(String name) throws Exception {
            return data(post("/processes"), Map.of("workflowId", workflow, "processName", name, "nodeType", "process"))
                .path("processId").asText();
        }

        String revisionPath() { return "/workflows/" + workflow + "/revisions/" + revision; }
        String body(String literal) throws Exception {
            String base = mapper.writeValueAsString(Map.of("workflowId", workflow, "fromProcessId", source, "toProcessId", target));
            return base.substring(0, base.length() - 1) + ",\"delayBasis\":" + literal + "}";
        }
        List<Map<String, Object>> rows() {
            return jdbc.queryForList("select * from process_connection where workflow_id=? order by connection_id", workflow);
        }
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
    }

    private JsonNode data(MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mapper.readTree(mvc.perform(auth(request)).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString()).path("data");
    }
}
