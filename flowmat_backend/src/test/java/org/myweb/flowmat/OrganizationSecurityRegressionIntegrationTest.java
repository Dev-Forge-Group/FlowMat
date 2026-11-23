package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.security.AuthUser;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** OR2-OR7: organization privileges never override project access or lose the last owner. No development DB fixtures. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class OrganizationSecurityRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectAccessService access;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void managersSeeOnlyMetadataAndDoNotGainProjectWriteOrOwnerAccess() throws Exception {
        String admin = user();
        String team = team();
        add(team, admin, "admin");
        String project = project(DEMO_OWNER, team);
        JsonNode metadata = success(admin, get("/organizations/" + team + "/projects"), null).get(0);
        var fields = new ArrayList<String>();
        metadata.fieldNames().forEachRemaining(fields::add);
        assertEquals(List.of("ownerId", "projectId", "projectName", "projectStatus"), fields.stream().sorted().toList());
        assertEquals(project, metadata.path("projectId").asText());
        assertEquals(List.of("FORBIDDEN", "FORBIDDEN", "FORBIDDEN"), permissions(admin, project));
        assertFalse(visible(admin).contains(project));
    }

    @Test
    void membersCannotManageAndAdminsCannotCreateOwnersOrChangeRoles() throws Exception {
        String admin = user();
        String member = user();
        String target = user();
        String team = team();
        String adminId = add(team, admin, "admin");
        String memberId = add(team, member, "member");
        perform(member, post("/organizations/" + team + "/members"), Map.of("userId", target)).andExpect(status().isForbidden());
        perform(member, get("/organizations/" + team + "/projects"), null).andExpect(status().isForbidden());
        perform(admin, post("/organizations/" + team + "/members"), Map.of("userId", target, "orgRole", "owner"))
            .andExpect(status().isForbidden());
        perform(admin, put("/organizations/" + team + "/members/" + memberId), Map.of("orgRole", "owner"))
            .andExpect(status().isForbidden());
        perform(admin, post("/organizations/" + team + "/members/" + adminId + "/remove"), null)
            .andExpect(status().isForbidden());
        assertEquals("member", jdbc.queryForObject("select org_role from organization_member where organization_member_id=?", String.class, memberId));
        assertEquals(0, jdbc.queryForObject("select count(*) from organization_member where organization_id=? and user_id=?", Integer.class, team, target));
    }

    @Test
    void memberIdentifiersCannotBeUsedAcrossOrganizationsEvenByAnOwnerOfBoth() throws Exception {
        String first = team();
        String second = team();
        String target = user();
        String foreignMember = add(second, target, "member");
        perform(DEMO_OWNER, put("/organizations/" + first + "/members/" + foreignMember), Map.of("orgRole", "admin"))
            .andExpect(status().isNotFound());
        perform(DEMO_OWNER, post("/organizations/" + first + "/members/" + foreignMember + "/remove"), null)
            .andExpect(status().isNotFound());
        perform(target, post("/organizations/" + first + "/members/" + foreignMember + "/leave"), null)
            .andExpect(status().isForbidden());
        assertEquals("active", jdbc.queryForObject("select member_status from organization_member where organization_member_id=?", String.class, foreignMember));
    }

    @Test
    void rejoiningAnOrganizationDoesNotRestoreRevokedProjectMemberships() throws Exception {
        String target = user();
        String first = team();
        String second = team();
        String original = add(first, target, "member");
        add(second, target, "member");
        String revokedProject = project(DEMO_OWNER, first);
        String retainedProject = project(DEMO_OWNER, second);
        projectMember(revokedProject, target, "editor");
        projectMember(retainedProject, target, "viewer");
        perform(DEMO_OWNER, post("/organizations/" + first + "/members/" + original + "/remove"), null).andExpect(status().isOk());
        assertEquals(List.of("FORBIDDEN", "FORBIDDEN", "FORBIDDEN"), permissions(target, revokedProject));
        assertEquals(List.of("OK", "FORBIDDEN", "FORBIDDEN"), permissions(target, retainedProject));
        String replacement = add(first, target, "member");
        assertNotEquals(original, replacement);
        assertEquals(List.of("FORBIDDEN", "FORBIDDEN", "FORBIDDEN"), permissions(target, revokedProject));
        perform(DEMO_OWNER, put("/organizations/" + first + "/members/" + original), Map.of("orgRole", "owner"))
            .andExpect(status().isNotFound());
    }

    @Test
    void backfillPreservesTheReadWriteOwnerAndProjectListMatrixAndIsIdempotent() throws Exception {
        String editor = user(), viewer = user(), outsider = user(), orgAdmin = user(), globalReader = user();
        String team = team();
        add(team, orgAdmin, "admin");
        String linked = project(DEMO_OWNER, team);
        String unlinked = project(DEMO_OWNER, null);
        projectMember(unlinked, editor, "editor");
        projectMember(unlinked, viewer, "viewer");
        jdbc.update("update project set organization_id=null where project_id=?", unlinked);
        jdbc.update("insert into user_roles(user_id,role_id,scope_type) select u.id,r.role_id,'global' from users u,roles r where u.user_id=? and r.role_name='admin'", globalReader);
        List<String> actors = List.of(DEMO_OWNER, editor, viewer, outsider, orgAdmin, globalReader);
        List<String> projects = List.of(unlinked, linked);
        Map<String, Object> before = matrix(actors, projects);
        assertEquals(List.of("OK", "OK", "OK"), permissions(DEMO_OWNER, unlinked));
        assertEquals(List.of("OK", "OK", "FORBIDDEN"), permissions(editor, unlinked));
        assertEquals(List.of("OK", "FORBIDDEN", "FORBIDDEN"), permissions(viewer, unlinked));
        assertEquals(List.of("FORBIDDEN", "FORBIDDEN", "FORBIDDEN"), permissions(globalReader, unlinked));
        assertTrue(visible(globalReader).contains(unlinked));
        var projectMembers = jdbc.queryForList("select * from project_member order by project_member_id");
        replayBackfill();
        assertEquals(before, matrix(actors, projects));
        assertEquals(projectMembers, jdbc.queryForList("select * from project_member order by project_member_id"));
        assertEquals(team, jdbc.queryForObject("select organization_id from project where project_id=?", String.class, linked));
        assertNotNull(jdbc.queryForObject("select organization_id from project where project_id=?", String.class, unlinked));
        int organizations = jdbc.queryForObject("select count(*) from organization", Integer.class);
        int memberships = jdbc.queryForObject("select count(*) from organization_member", Integer.class);
        replayBackfill();
        assertEquals(before, matrix(actors, projects));
        assertEquals(organizations, jdbc.queryForObject("select count(*) from organization", Integer.class));
        assertEquals(memberships, jdbc.queryForObject("select count(*) from organization_member", Integer.class));
        assertEquals(projectMembers, jdbc.queryForList("select * from project_member order by project_member_id"));
    }

    @ParameterizedTest
    @CsvSource({"role,role", "role,leave", "leave,remove"})
    @Timeout(45)
    void simultaneousOwnerChangesKeepOneActiveOwner(String firstOperation, String secondOperation) throws Exception {
        String secondOwner = user();
        String team = team();
        String secondMember = add(team, secondOwner, "owner");
        String firstMember = jdbc.queryForObject("select organization_member_id from organization_member where organization_id=? and user_id=? and member_status='active'", String.class, team, DEMO_OWNER);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var pending = new TransactionTemplate(transactions).execute(tx -> {
                jdbc.queryForObject("select organization_id from organization where organization_id=? for update", String.class, team);
                int blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                var first = pool.submit(() -> ownerChange(DEMO_OWNER, team, firstMember, firstOperation));
                var second = pool.submit(() -> ownerChange(secondOwner, team, secondMember, secondOperation));
                DatabaseContention.awaitWaitingOrDone(jdbc, first, blocker);
                DatabaseContention.awaitWaitingOrDone(jdbc, second, blocker);
                assertFalse(first.isDone());
                assertFalse(second.isDone());
                return List.of(first, second);
            });
            var statuses = new ArrayList<Integer>();
            for (var request : pending) statuses.add(request.get(20, TimeUnit.SECONDS));
            assertEquals(List.of(200, 409), statuses.stream().sorted().toList());
            assertEquals(1, jdbc.queryForObject("select count(*) from organization_member where organization_id=? and org_role='owner' and member_status='active'", Integer.class, team));
        }
    }

    @Test
    @Timeout(45)
    void removalCannotRaceWithCreatingANewProjectInTheOrganization() throws Exception {
        String creator = user();
        String team = team();
        String membership = add(team, creator, "member");
        String name = "Revocation race " + UUID.randomUUID();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var pending = new TransactionTemplate(transactions).execute(tx -> {
                try {
                    perform(DEMO_OWNER, post("/organizations/" + team + "/members/" + membership + "/remove"), null)
                        .andExpect(status().isOk());
                } catch (Exception error) {
                    throw new IllegalStateException("Cannot prepare membership revocation", error);
                }
                int blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                var creation = pool.submit(() -> perform(creator, post("/projects"),
                    Map.of("projectName", name, "ownerId", creator, "organizationId", team))
                    .andReturn().getResponse().getStatus());
                DatabaseContention.awaitWaitingOrDone(jdbc, creation, blocker);
                return creation;
            });
            assertEquals(403, pending.get(20, TimeUnit.SECONDS), "A creator removed from the organization cannot create its project.");
            assertEquals(0, jdbc.queryForObject("select count(*) from project where organization_id=? and project_name=?", Integer.class, team, name));
        }
    }

    private int ownerChange(String actor, String team, String member, String operation) throws Exception {
        String path = "/organizations/" + team + "/members/" + member;
        return perform(actor, "role".equals(operation) ? put(path) : post(path + "/" + operation),
            "role".equals(operation) ? Map.of("orgRole", "member") : null).andReturn().getResponse().getStatus();
    }

    private Map<String, Object> matrix(List<String> actors, List<String> projects) {
        var matrix = new LinkedHashMap<String, Object>();
        for (String actor : actors) {
            matrix.put(actor + ":list", visible(actor));
            for (String project : projects) matrix.put(actor + ":" + project, permissions(actor, project));
        }
        return matrix;
    }

    private List<String> permissions(String actor, String project) {
        var previous = SecurityContextHolder.getContext().getAuthentication();
        try {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthUser(actor), null));
            return List.of(decision(() -> access.requireProjectReadAccess(project)),
                decision(() -> access.requireProjectWriteAccess(project)), decision(() -> access.requireProjectOwnerAccess(project)));
        } finally { SecurityContextHolder.getContext().setAuthentication(previous); }
    }

    private List<String> visible(String actor) {
        var previous = SecurityContextHolder.getContext().getAuthentication();
        try {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthUser(actor), null));
            return access.listAccessibleProjects().stream().map(project -> project.getProjectId()).sorted().toList();
        } finally { SecurityContextHolder.getContext().setAuthentication(previous); }
    }

    private String decision(Runnable action) {
        try { action.run(); return "OK"; }
        catch (BusinessException error) { return error.getErrorCode().name(); }
    }

    private void replayBackfill() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V65__organization_personal_backfill.sql"));
            return null;
        });
    }

    private String team() throws Exception {
        return success(DEMO_OWNER, post("/organizations"), Map.of("organizationName", "Security " + UUID.randomUUID())).path("organizationId").asText();
    }

    private String add(String team, String user, String role) throws Exception {
        return success(DEMO_OWNER, post("/organizations/" + team + "/members"), Map.of("userId", user, "orgRole", role)).path("organizationMemberId").asText();
    }

    private String project(String owner, String organization) throws Exception {
        var body = new LinkedHashMap<String, Object>(Map.of("projectName", "Security " + UUID.randomUUID(), "ownerId", owner));
        if (organization != null) body.put("organizationId", organization);
        return success(owner, post("/projects"), body).path("projectId").asText();
    }

    private String user() {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        return id;
    }

    private void projectMember(String project, String user, String role) {
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role,member_status,invited_by) values(?,?,?,?,'active',?)", UUID.randomUUID().toString(), project, user, role, DEMO_OWNER);
    }

    private JsonNode success(String user, MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return json.readTree(perform(user, request, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private org.springframework.test.web.servlet.ResultActions perform(String user, MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        return mvc.perform(request);
    }
}
