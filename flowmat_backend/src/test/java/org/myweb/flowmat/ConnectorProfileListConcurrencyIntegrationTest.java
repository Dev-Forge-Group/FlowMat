package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.myweb.flowmat.domain.connector.application.ConnectorTargetPolicy;
import org.myweb.flowmat.domain.connector.repository.ConnectorProfileRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class ConnectorProfileListConcurrencyIntegrationTest extends IntegrationTestSupport {
    private static final String ROUTE = "/projects/" + DEMO_PROJECT + "/http-profiles";
    private static final String BODY = "{\"name\":\"Before\",\"baseOrigin\":\"https://before.example.com\",\"credentialAlias\":null,"
        + "\"enabled\":true,\"operations\":[{\"operationId\":\"read\",\"method\":\"GET\",\"relativePath\":\"/before\",\"successStatuses\":[200]}]}";
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired ObjectMapper json;
    @MockitoBean ConnectorTargetPolicy targets;
    @MockitoSpyBean ConnectorProfileRepository profiles;

    @BeforeEach
    void configureApprovedOrigin() {
        when(targets.normalizeOrigin(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test @Timeout(60)
    void updateAfterTheListReadCannotMixTheOldProfileWithTheNewRevision() throws Exception {
        verifyConcurrentMutation(false);
    }

    @Test @Timeout(60)
    void deletionAfterTheListReadCannotTurnAValidListIntoAServerError() throws Exception {
        verifyConcurrentMutation(true);
    }

    private void verifyConcurrentMutation(boolean deleting) throws Exception {
        JsonNode created = data(post(ROUTE), BODY);
        String id = created.path("profileId").asText();
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean pauseOnce = new AtomicBoolean(true);
        var delegatedQuery = mockingDetails(profiles).getMockCreationSettings().getDefaultAnswer();
        doAnswer(call -> {
            Object result = delegatedQuery.answer(call);
            if (pauseOnce.compareAndSet(true, false)) {
                read.countDown();
                assertTrue(resume.await(15, TimeUnit.SECONDS), "The first list query was not released.");
            }
            return result;
        }).when(profiles).findAllByProjectIdAndDeletedYnOrderByCreatedAtDescProfileIdAsc(DEMO_PROJECT, "N");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var listing = executor.submit(() -> data(get(ROUTE), null));
            if (!read.await(15, TimeUnit.SECONDS)) {
                if (listing.isDone()) listing.get(1, TimeUnit.SECONDS);
                fail("The list did not reach its first completed database query.");
            }
            JsonNode updated = null;
            try {
                if (deleting) call(delete(ROUTE + "/" + id).param("expectedVersion", "0"), null).andExpect(status().isOk());
                else updated = data(put(ROUTE + "/" + id), "{\"expectedVersion\":0," + BODY.substring(1)
                    .replace("Before", "After").replace("before.example.com", "after.example.com").replace("/before", "/after"));
            } finally { resume.countDown(); }
            JsonNode snapshot = find(listing.get(15, TimeUnit.SECONDS), id);
            assertNotNull(snapshot, "The captured read contained this active profile.");
            for (String field : new String[] {"name", "version", "currentRevision", "profileVersionId", "baseOrigin", "operations", "enabled"}) {
                assertEquals(created.path(field), snapshot.path(field), "Mixed profile snapshot field: " + field);
            }
            JsonNode current = find(data(get(ROUTE), null), id);
            if (deleting) assertNull(current);
            else {
                assertNotNull(current);
                assertEquals(updated.path("profileVersionId"), current.path("profileVersionId"));
                assertEquals(1, current.path("version").asLong());
                assertEquals(2, current.path("currentRevision").asInt());
                assertEquals("After", current.path("name").asText());
                assertEquals("https://after.example.com", current.path("baseOrigin").asText());
            }
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Owned listing worker did not stop.");
        }
    }

    private JsonNode find(JsonNode rows, String id) {
        for (JsonNode row : rows) if (id.equals(row.path("profileId").asText())) return row;
        return null;
    }

    private JsonNode data(MockHttpServletRequestBuilder request, String body) throws Exception {
        return json.readTree(call(request, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }
}
