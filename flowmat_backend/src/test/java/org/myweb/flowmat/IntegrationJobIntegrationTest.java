package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.connector.application.ConnectorTargetPolicy;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorHttpGateway;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorHttpOutcome;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Integration jobs that pull items and stock (docs/architecture/adr/ADR-038-integration-jobs.md) against real Postgres,
 * with the connector's HTTP gateway answering what each test needs.
 */
@AutoConfigureMockMvc
class IntegrationJobIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private ConnectorTargetPolicy targets;
    @MockitoBean private ConnectorHttpGateway gateway;

    private final AtomicReference<String> answer = new AtomicReference<>();

    @BeforeEach
    void source() {
        reset(targets, gateway);
        when(targets.normalizeOrigin(any())).thenAnswer(call -> call.getArgument(0));
        doAnswer(call -> {
            // The source is read with no transaction open (ADR-038 decision 5).
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String body = answer.get();
            return body == null
                ? new ConnectorHttpOutcome("HTTP_REJECTED", 503, null, "HTTP_STATUS_REJECTED", true)
                : new ConnectorHttpOutcome("SUCCEEDED", 200, json.readTree(body), null, true);
        }).when(gateway).execute(any(), any());
    }

    @Test
    void aPullJobTakesItemsByTheImportsRulesLeavingOutTheRecordsWithProblems() throws Exception {
        String tag = tag();
        String version = profile();
        JsonNode job = data(call(DEMO_OWNER, post(jobs()), """
            {"name":"Supplier catalogue","kind":"pull_items","profileVersionId":"%s","operationId":"list",
             "mapping":{"rowsAt":"/data/items","row":{"object":{
               "itemCode":{"from":"/sku"},"itemName":{"from":"/title"},"unitCode":{"literal":"KG"},
               "unitCost":{"from":"/price","required":false}}}}}
            """.formatted(version)));
        String jobId = job.path("jobId").asText();
        assertThat(job.path("version").asInt()).isEqualTo(1);

        answer.set("""
            {"data":{"items":[
              {"sku":"INT-A-%1$s","title":"Oats","price":2.5},
              {"sku":"INT-B-%1$s","title":"","price":1},
              {"sku":"INT-C-%1$s","title":"Rye","price":{"amount":3}},
              {"sku":"INT-D-%1$s","title":"Spelt"}]}}
            """.formatted(tag));

        // A preview reads and checks, and saves nothing: no item, no run.
        JsonNode preview = data(call(DEMO_OWNER, post(jobs() + "/" + jobId + "/preview"), null));
        assertThat(preview.path("status").asText()).isEqualTo("partial");
        assertThat(preview.path("rowsTotal").asInt()).isEqualTo(4);
        assertThat(preview.path("rowsLeftOut").asInt()).isEqualTo(2);
        assertThat(preview.path("items").get(0).path("result").asText()).isEqualTo("create");
        assertThat(preview.path("items").get(1).path("result").asText()).isEqualTo("left_out");
        assertThat(preview.path("items").get(1).path("message").asText()).containsIgnoringCase("name");
        assertThat(preview.path("items").get(2).path("message").asText()).isEqualTo("unitCost is not a single value");
        assertThat(count("select count(*) from item where item_code like ?", "INT-%-" + tag)).isZero();
        assertThat(count("select count(*) from integration_run where job_id = ?", jobId)).isZero();

        // A run saves the good records together and keeps what happened to each.
        JsonNode run = data(call(DEMO_OWNER, post(jobs() + "/" + jobId + "/runs"), null));
        assertThat(run.path("status").asText()).isEqualTo("partial");
        assertThat(run.path("trigger").asText()).isEqualTo("manual");
        assertThat(run.path("runBy").asText()).isEqualTo(DEMO_OWNER);
        assertThat(run.path("rowsApplied").asInt()).isEqualTo(2);
        assertThat(run.path("message").asText()).isEqualTo("2 of 4 records were left out.");
        assertThat(count("select count(*) from item where item_code in (?, ?)", "INT-A-" + tag, "INT-D-" + tag)).isEqualTo(2);
        assertThat(count("select count(*) from item where item_code in (?, ?)", "INT-B-" + tag, "INT-C-" + tag)).isZero();
        assertThat(jdbc.queryForObject("select unit_cost from item where item_code = ?", java.math.BigDecimal.class, "INT-A-" + tag))
            .isEqualByComparingTo("2.5");

        // The same records again change nothing, and the run says so.
        JsonNode again = data(call(DEMO_OWNER, post(jobs() + "/" + jobId + "/runs"), null));
        assertThat(again.path("items").get(0).path("result").asText()).isEqualTo("unchanged");

        JsonNode history = data(call(DEMO_OWNER, get(jobs() + "/" + jobId + "/runs"), null));
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).path("runId").asText()).isEqualTo(again.path("runId").asText());
        assertThat(data(call(DEMO_OWNER, get(jobs()), null)).findValues("jobId").stream().map(JsonNode::asText))
            .contains(jobId);
    }

    @Test
    void aStockJobReceivesWithTheJobsNoteAndAFailedReadIsKept() throws Exception {
        String tag = tag();
        String flour = "INT-F-" + tag;
        call(DEMO_OWNER, post("/items"), json.writeValueAsString(java.util.Map.of("projectId", DEMO_PROJECT, "itemCode", flour,
            "itemName", "Flour", "unitId", "unit_kg"))).andExpect(status().isOk());
        String jobId = data(call(DEMO_OWNER, post(jobs()), """
            {"name":"Warehouse levels","kind":"pull_stock","profileVersionId":"%s","operationId":"list","note":"nightly sync",
             "mapping":{"rowsAt":"","row":{"object":{"itemCode":{"from":"/code"},"quantity":{"from":"/qty"},
               "location":{"from":"/bin","required":false}}}}}
            """.formatted(profile()))).path("jobId").asText();

        answer.set("[{\"code\":\"%s\",\"qty\":7},{\"code\":\"NOPE-%s\",\"qty\":1}]".formatted(flour, tag));
        JsonNode run = data(call(DEMO_OWNER, post(jobs() + "/" + jobId + "/runs"), null));
        assertThat(run.path("status").asText()).isEqualTo("partial");
        assertThat(run.path("items").get(0).path("result").asText()).isEqualTo("create");
        assertThat(run.path("items").get(1).path("result").asText()).isEqualTo("left_out");
        assertThat(jdbc.queryForObject("select coalesce(sum(i.quantity), 0) from inventory i join item t on t.item_id = i.item_id "
            + "where t.item_code = ?", java.math.BigDecimal.class, flour)).isEqualByComparingTo("7");

        // The source answers with an error: the run is kept as failed, and nothing else changes.
        answer.set(null);
        JsonNode failed = data(call(DEMO_OWNER, post(jobs() + "/" + jobId + "/runs"), null));
        assertThat(failed.path("status").asText()).isEqualTo("failed");
        assertThat(failed.path("message").asText()).isEqualTo("The source answered HTTP 503.");

        // A response without the list the job names fails as a whole.
        answer.set("{\"rows\":[]}");
        assertThat(data(call(DEMO_OWNER, post(jobs() + "/" + jobId + "/runs"), null)).path("message").asText())
            .isEqualTo("The response has no list of records at the top of the response.");
    }

    @Test
    void aJobIsCheckedWhenSavedChangedOnItsVersionAndOnlyByTheOwner() throws Exception {
        String version = profile();
        String good = """
            {"name":"Catalogue","kind":"pull_items","profileVersionId":"%s","operationId":"list",
             "mapping":{"rowsAt":"/items","row":{"object":{"itemCode":{"from":"/sku"}}}}}
            """.formatted(version);
        call(DEMO_OWNER, post(jobs()), good.replace("\"itemCode\"", "\"colour\"")).andExpect(status().isBadRequest());
        call(DEMO_OWNER, post(jobs()), good.replace("\"list\"", "\"send\"")).andExpect(status().isBadRequest());
        call(DEMO_OWNER, post(jobs()), good.replace("\"/items\"", "\"items\"")).andExpect(status().isBadRequest());
        call(DEMO_OWNER, post(jobs()), good.replace("pull_items", "push_orders")).andExpect(status().isBadRequest());
        call(DEMO_OWNER, post(jobs()), good.replace("\"list\"", "\"missing\"")).andExpect(status().isBadRequest());

        JsonNode job = data(call(DEMO_OWNER, post(jobs()), good));
        String path = jobs() + "/" + job.path("jobId").asText();
        String changed = good.replace("Catalogue", "Catalogue v2").replace("\"mapping\"", "\"version\":1,\"mapping\"");
        assertThat(data(call(DEMO_OWNER, put(path), changed)).path("version").asInt()).isEqualTo(2);
        call(DEMO_OWNER, put(path), changed).andExpect(status().isConflict());

        String viewer = member("viewer");
        call(viewer, get(jobs()), null).andExpect(status().isOk());
        call(viewer, post(path + "/runs"), null).andExpect(status().isForbidden());
        call(viewer, post(path + "/preview"), null).andExpect(status().isForbidden());
        call(viewer, post(jobs()), good).andExpect(status().isForbidden());
        verify(gateway, never()).execute(any(), any());

        call(DEMO_OWNER, delete(path), null).andExpect(status().isOk());
        call(DEMO_OWNER, post(path + "/runs"), null).andExpect(status().isNotFound());
        verify(gateway, times(0)).execute(any(), any());
    }

    private String profile() throws Exception {
        return data(call(DEMO_OWNER, post("/projects/" + DEMO_PROJECT + "/http-profiles"), """
            {"name":"Supplier %s","baseOrigin":"https://supplier.example.com","enabled":true,
             "operations":[{"operationId":"list","method":"GET","relativePath":"/items","successStatuses":[200]},
                           {"operationId":"send","method":"POST","relativePath":"/stock","successStatuses":[200]}]}
            """.formatted(tag()))).path("profileVersionId").asText();
    }

    private String member(String role) {
        String user = "integration-" + role + "-" + tag();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", user, user + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), DEMO_PROJECT, user, role);
        return user;
    }

    private static String jobs() {
        return "/projects/" + DEMO_PROJECT + "/integration-jobs";
    }

    private static String tag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(String user, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }
}
