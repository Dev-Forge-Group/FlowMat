package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.myweb.flowmat.domain.project.domain.entity.ProjectMember;
import org.myweb.flowmat.domain.project.repository.ProjectMemberRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Regression guard for cross-tenant access (IDOR): a signed-in user who is not a member of the demo project
 * must not be able to read or change its data through any project-scoped endpoint.
 *
 * <p>Add new project-scoped endpoints to {@link #projectScopedRequests()} when they are introduced.
 */
@AutoConfigureMockMvc
class ProjectAccessIntegrationTest extends IntegrationTestSupport {

    private static final String OUTSIDER = "outsider-user";
    private static final String VIEWER = "viewer-user";

    /** Seeded by V2__seed_demo.sql. */
    private static final String DEMO_PROCESS = "prc_demo_input";
    private static final String DEMO_PROCESS_IO = "pio_demo_input_out";
    private static final String DEMO_CONNECTION = "pcn_demo_input_to_mix";
    private static final String DEMO_ITEM = "itm_demo_mix_output";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

    static Stream<Arguments> projectScopedRequests() {
        return Stream.of(
            Arguments.of("list items", get("/items").param("projectId", DEMO_PROJECT)),
            Arguments.of("list workflows", get("/workflows").param("projectId", DEMO_PROJECT)),
            Arguments.of("list flow rules", get("/flow-rules").param("projectId", DEMO_PROJECT)),
            Arguments.of("list inventories", get("/inventories").param("projectId", DEMO_PROJECT)),
            Arguments.of("list lots", get("/lots").param("projectId", DEMO_PROJECT)),
            Arguments.of("list inventory transactions", get("/inventory-transactions").param("projectId", DEMO_PROJECT)),
            Arguments.of("list production runs", get("/production-runs").param("workflowId", DEMO_WORKFLOW)),
            Arguments.of("start production run", post("/production-runs/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowId\":\"" + DEMO_WORKFLOW
                    + "\",\"plannedOutputQty\":1}")),
            Arguments.of("create inventory", post("/inventories")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"any\",\"quantity\":1}")),
            Arguments.of("read another user's payments", get("/payments/users/" + DEMO_OWNER)),
            Arguments.of("create a global unit", post("/units")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitCode\":\"pkg\",\"unitName\":\"Package\",\"unitType\":\"count\"}")),
            Arguments.of("list work orders", get("/work-orders").param("projectId", DEMO_PROJECT)),
            Arguments.of("create work order", post("/work-orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workOrderTitle\":\"x\"}")),

            // Project and membership.
            Arguments.of("read project", get("/projects/" + DEMO_PROJECT)),
            Arguments.of("rename project", json(put("/projects/" + DEMO_PROJECT), "{\"projectName\":\"hijacked\"}")),
            Arguments.of("delete project", delete("/projects/" + DEMO_PROJECT)),
            Arguments.of("list project members", get("/project-members").param("projectId", DEMO_PROJECT)),
            Arguments.of("list project invites", get("/project-invites").param("projectId", DEMO_PROJECT)),
            Arguments.of("invite into project", json(post("/project-invites"),
                "{\"projectId\":\"" + DEMO_PROJECT + "\",\"invitedEmail\":\"attacker@example.com\",\"projectRole\":\"owner\"}")),

            // Workflow and canvas.
            Arguments.of("create workflow", json(post("/workflows"),
                "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"x\"}")),
            Arguments.of("read workflow", get("/workflows/" + DEMO_WORKFLOW)),
            Arguments.of("rename workflow", json(put("/workflows/" + DEMO_WORKFLOW), "{\"workflowName\":\"hijacked\"}")),
            Arguments.of("delete workflow", delete("/workflows/" + DEMO_WORKFLOW)),
            Arguments.of("read canvas", get("/workflows/" + DEMO_WORKFLOW + "/canvas")),
            Arguments.of("read graph changes", get("/workflows/" + DEMO_WORKFLOW + "/graph-changes")),
            Arguments.of("read presence", get("/workflows/" + DEMO_WORKFLOW + "/presence")),
            Arguments.of("read editor document", get("/workflows/" + DEMO_WORKFLOW + "/editor-document")),
            Arguments.of("save editor document", json(put("/workflows/" + DEMO_WORKFLOW + "/editor-document"),
                "{\"expectedVersion\":0,\"elements\":[]}")),
            Arguments.of("change editor elements", json(patch("/workflows/" + DEMO_WORKFLOW + "/editor-document/elements"),
                "{\"changes\":[{\"deleteId\":\"shape-1\",\"baseVersion\":1}]}")),
            Arguments.of("list annotations", get("/workflows/" + DEMO_WORKFLOW + "/annotations")),
            Arguments.of("create annotation", json(post("/workflows/" + DEMO_WORKFLOW + "/annotations"),
                "{\"annotationType\":\"shape\",\"shapeKind\":\"rectangle\",\"posX\":0,\"posY\":0}")),

            // Processes, IOs and connections.
            Arguments.of("list processes", get("/processes").param("workflowId", DEMO_WORKFLOW)),
            Arguments.of("create process", json(post("/processes"),
                "{\"workflowId\":\"" + DEMO_WORKFLOW + "\",\"processName\":\"x\"}")),
            Arguments.of("read process", get("/processes/" + DEMO_PROCESS)),
            Arguments.of("update process", json(put("/processes/" + DEMO_PROCESS), "{\"processName\":\"hijacked\"}")),
            Arguments.of("move process", json(patch("/processes/" + DEMO_PROCESS + "/position"), "{\"posX\":1,\"posY\":1}")),
            Arguments.of("delete process", delete("/processes/" + DEMO_PROCESS)),
            Arguments.of("list process IOs", get("/process-ios").param("processId", DEMO_PROCESS)),
            Arguments.of("create process IO", json(post("/process-ios"),
                "{\"processId\":\"" + DEMO_PROCESS + "\",\"itemId\":\"" + DEMO_ITEM
                    + "\",\"direction\":\"input\",\"quantity\":1,\"unit\":\"kg\"}")),
            Arguments.of("read process IO", get("/process-ios/" + DEMO_PROCESS_IO)),
            Arguments.of("update process IO", json(put("/process-ios/" + DEMO_PROCESS_IO), "{\"ioName\":\"hijacked\"}")),
            Arguments.of("delete process IO", delete("/process-ios/" + DEMO_PROCESS_IO)),
            Arguments.of("list connections", get("/process-connections").param("workflowId", DEMO_WORKFLOW)),
            Arguments.of("create connection", json(post("/process-connections"),
                "{\"workflowId\":\"" + DEMO_WORKFLOW + "\",\"fromProcessId\":\"prc_demo_mix\",\"toProcessId\":\""
                    + DEMO_PROCESS + "\"}")),
            Arguments.of("read connection", get("/process-connections/" + DEMO_CONNECTION)),
            Arguments.of("update connection", json(put("/process-connections/" + DEMO_CONNECTION),
                "{\"connectionLabel\":\"hijacked\"}")),
            Arguments.of("delete connection", delete("/process-connections/" + DEMO_CONNECTION)),

            // Catalog and rules.
            Arguments.of("create item", json(post("/items"),
                "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemCode\":\"X\",\"itemName\":\"x\"}")),
            Arguments.of("read item", get("/items/" + DEMO_ITEM)),
            Arguments.of("update item", json(put("/items/" + DEMO_ITEM), "{\"itemName\":\"hijacked\"}")),
            Arguments.of("delete item", delete("/items/" + DEMO_ITEM)),
            Arguments.of("create flow rule", json(post("/flow-rules"),
                "{\"projectId\":\"" + DEMO_PROJECT + "\",\"targetType\":\"workflow\",\"targetId\":\"" + DEMO_WORKFLOW
                    + "\",\"ruleName\":\"x\",\"conditionExpression\":\"a > 1\"}")),
            Arguments.of("create process template", json(post("/process-templates"),
                "{\"templateName\":\"x\",\"templateCategory\":\"process\"}")),
            Arguments.of("create workflow template", json(post("/workflow-templates"),
                "{\"templateName\":\"x\",\"templateCategory\":\"workflow\"}")),

            // BOM.
            Arguments.of("list BOMs", get("/boms").param("projectId", DEMO_PROJECT)),
            Arguments.of("create BOM", json(post("/boms"),
                "{\"projectId\":\"" + DEMO_PROJECT + "\",\"targetItemId\":\"" + DEMO_ITEM
                    + "\",\"bomName\":\"x\",\"baseQuantity\":1,\"baseUnit\":\"ea\"}")),

            // LOT.
            Arguments.of("list LOTs", get("/lots").param("projectId", DEMO_PROJECT)),
            Arguments.of("create LOT", json(post("/lots"),
                "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + DEMO_ITEM + "\",\"lotNo\":\"OUTSIDER-1\"}")),

            // System administration needs a system role, not project membership.
            Arguments.of("list users as admin", get("/admin/users"))
        );
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @ParameterizedTest(name = "outsider cannot {0}")
    @MethodSource("projectScopedRequests")
    void outsiderIsForbidden(String description, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request.header("Authorization", bearer(OUTSIDER)))
            .andExpect(status().isForbidden());
    }

    static Stream<Arguments> writeRequests() {
        return Stream.of(
            Arguments.of("rename workflow", json(put("/workflows/" + DEMO_WORKFLOW), "{\"workflowName\":\"viewer edit\"}")),
            Arguments.of("create process", json(post("/processes"),
                "{\"workflowId\":\"" + DEMO_WORKFLOW + "\",\"processName\":\"x\"}")),
            Arguments.of("move process", json(patch("/processes/" + DEMO_PROCESS + "/position"), "{\"posX\":1,\"posY\":1}")),
            Arguments.of("update item", json(put("/items/" + DEMO_ITEM), "{\"itemName\":\"viewer edit\"}")),
            Arguments.of("create inventory", json(post("/inventories"),
                "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + DEMO_ITEM + "\",\"quantity\":1}")),
            Arguments.of("start production run", json(post("/production-runs/start"),
                "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowId\":\"" + DEMO_WORKFLOW + "\",\"plannedOutputQty\":1}")),
            Arguments.of("create work order", json(post("/work-orders"),
                "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workOrderTitle\":\"x\"}")),
            Arguments.of("create flow rule", json(post("/flow-rules"),
                "{\"projectId\":\"" + DEMO_PROJECT + "\",\"targetType\":\"workflow\",\"targetId\":\"" + DEMO_WORKFLOW
                    + "\",\"ruleName\":\"x\",\"conditionExpression\":\"a > 1\"}")),
            Arguments.of("invite into project", json(post("/project-invites"),
                "{\"projectId\":\"" + DEMO_PROJECT + "\",\"invitedEmail\":\"friend@example.com\"}")),
            Arguments.of("delete project", delete("/projects/" + DEMO_PROJECT))
        );
    }

    @ParameterizedTest(name = "viewer cannot {0}")
    @MethodSource("writeRequests")
    void viewerCannotWrite(String description, MockHttpServletRequestBuilder request) throws Exception {
        ensureViewerMember();
        mockMvc.perform(request.header("Authorization", bearer(VIEWER)))
            .andExpect(status().isForbidden());
    }

    @Test
    void viewerCanReadProjectData() throws Exception {
        ensureViewerMember();
        mockMvc.perform(get("/workflows/" + DEMO_WORKFLOW + "/canvas").header("Authorization", bearer(VIEWER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.currentUserRole").value("viewer"));
        mockMvc.perform(get("/inventories").param("projectId", DEMO_PROJECT).header("Authorization", bearer(VIEWER)))
            .andExpect(status().isOk());
    }

    private void ensureViewerMember() {
        if (projectMemberRepository.existsByProjectIdAndUserIdAndMemberStatus(DEMO_PROJECT, VIEWER, "active")) {
            return;
        }
        ProjectMember member = new ProjectMember();
        member.setProjectMemberId("pm-viewer-" + UUID.randomUUID().toString().substring(0, 8));
        member.setProjectId(DEMO_PROJECT);
        member.setUserId(VIEWER);
        member.setProjectRole("viewer");
        member.setMemberStatus("active");
        member.setInvitedBy(DEMO_OWNER);
        member.setJoinedAt(OffsetDateTime.now());
        projectMemberRepository.save(member);
    }

    @Test
    void ownerCanReadOwnProjectData() throws Exception {
        // Guards against the matrix above passing only because every request is rejected.
        mockMvc.perform(get("/inventories").param("projectId", DEMO_PROJECT).header("Authorization", bearer(DEMO_OWNER)))
            .andExpect(status().isOk());
        mockMvc.perform(get("/production-runs").param("workflowId", DEMO_WORKFLOW)
                .header("Authorization", bearer(DEMO_OWNER)))
            .andExpect(status().isOk());
    }

    @Test
    void projectListsOnlyShowProjectsTheCallerBelongsTo() throws Exception {
        // /admin/projects currently returns the caller's own projects (same as /projects), not every project.
        for (String path : new String[] {"/projects", "/admin/projects"}) {
            mockMvc.perform(get(path).header("Authorization", bearer(OUTSIDER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.projectId == '" + DEMO_PROJECT + "')]").isEmpty());
        }
    }

    @Test
    void anySignedInUserCanReadSeededUnits() throws Exception {
        mockMvc.perform(get("/units").header("Authorization", bearer(OUTSIDER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.unitCode == 'kg')].unitType").value("mass"))
            .andExpect(jsonPath("$.data[?(@.unitCode == 'g')].conversionRate").value(0.001));
    }

    @Test
    void anonymousRequestIsUnauthorized() throws Exception {
        mockMvc.perform(get("/inventories").param("projectId", DEMO_PROJECT))
            .andExpect(status().isUnauthorized());
    }

    private String bearer(String userId) {
        return "Bearer " + jwtProvider.generateAccessToken(userId);
    }
}
