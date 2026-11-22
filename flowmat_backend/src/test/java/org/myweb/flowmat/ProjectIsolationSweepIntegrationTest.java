package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Factories kept apart, for every read the API has (docs/status/ROADMAP-2026-11-23.md §8 next month 2): someone who owns a
 * project of their own and is not a member of the demo project calls every GET endpoint with the demo project's ids and
 * must never get its data back. Unlike {@link ProjectAccessIntegrationTest}'s list, nobody has to remember to add a new
 * endpoint: the sweep reads the handler mappings. An id it cannot fill (no demo row, an unknown name) leaves that endpoint
 * out, and the list of covered endpoints is printed.
 */
@AutoConfigureMockMvc
class ProjectIsolationSweepIntegrationTest extends IntegrationTestSupport {

    /** Reads that answer the same for everyone or only about the caller, so a 200 is right. */
    private static final List<String> NOT_PROJECT_DATA = List.of(
        "/units", "/users/me", "/auth/", "/organizations", "/process-templates", "/workflow-templates", "/notifications",
        "/project-invites/me", "/projects/me", "/me/");

    /** Seeded by V2__seed_demo.sql. */
    private static final String DEMO_ITEM = "itm_demo_mix_output";

    private static final Pattern VARIABLE = Pattern.compile("\\{([A-Za-z0-9_]+)(?::[^}]*)?}");

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping mappings;

    /** The outsider's own project, whose rows are never used to fill an id. */
    private String otherProject;

    @Test
    void someoneFromAnotherProjectReadsNothingOfThisOne() throws Exception {
        String other = "iso-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", other, other + "@test.local", DEMO_OWNER);
        mockMvc.perform(post("/projects").header("Authorization", bearer(other)).contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(Map.of("projectName", "Other factory", "ownerId", other))));
        // A row of the kinds the seed lacks, so their reads by id are swept too; one that fails is just not covered.
        String tag = other.substring(4);
        for (Map.Entry<String, Map<String, Object>> seed : Map.<String, Map<String, Object>>of(
            "/equipments", Map.of("projectId", DEMO_PROJECT, "equipmentCode", "ISO-" + tag, "equipmentName", "iso", "equipmentType", "machine"),
            "/work-orders", Map.of("projectId", DEMO_PROJECT, "workOrderTitle", "iso " + tag),
            "/lots", Map.of("projectId", DEMO_PROJECT, "itemId", DEMO_ITEM, "lotNo", "ISO-" + tag),
            "/boms", Map.of("projectId", DEMO_PROJECT, "targetItemId", DEMO_ITEM, "bomName", "iso " + tag, "baseQuantity", 1, "baseUnit", "ea"),
            "/flow-rules", Map.of("projectId", DEMO_PROJECT, "targetType", "workflow", "targetId", DEMO_WORKFLOW, "ruleName", "iso " + tag,
                "conditionExpression", "a > 1")).entrySet()) {
            mockMvc.perform(post(seed.getKey()).header("Authorization", bearer(DEMO_OWNER)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(seed.getValue())));
        }
        otherProject = jdbc.queryForObject("select project_id from project where owner_id=? order by created_at desc limit 1",
            String.class, other);

        Map<String, String> ids = new LinkedHashMap<>();
        List<String> covered = new ArrayList<>();
        List<String> leaks = new ArrayList<>();
        TreeSet<String> unclear = new TreeSet<>();
        TreeSet<String> skipped = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            if (!info.getMethodsCondition().getMethods().contains(RequestMethod.GET)) continue;
            for (String pattern : info.getPatternValues()) {
                if (NOT_PROJECT_DATA.stream().anyMatch(pattern::startsWith)) continue;
                Optional<MockHttpServletRequestBuilder> request = request(pattern, entry.getValue(), ids);
                if (request.isEmpty()) {
                    skipped.add(pattern);
                    continue;
                }
                int status = mockMvc.perform(request.get().header("Authorization", bearer(other))).andReturn().getResponse().getStatus();
                covered.add(pattern + " " + status);
                if (status < 300 || status >= 500) leaks.add(pattern + " answered " + status);
                else if (status != 403 && status != 404) unclear.add(pattern + " answered " + status);
            }
        }
        System.out.println("Isolation sweep covered " + covered.size() + " reads:\n  " + String.join("\n  ", covered));
        System.out.println("Left out (an id or value it cannot fill, or nothing project-scoped): " + skipped.size()
            + "\n  " + String.join("\n  ", skipped));
        if (!unclear.isEmpty()) System.out.println("Neither 403 nor 404 (not data, worth a look):\n  " + String.join("\n  ", unclear));
        assertThat(leaks).as("reads that gave another project's data or failed").isEmpty();
        // The demo seed and the rows made above reach 123 when this class runs alone (2026-11-22); other tests only add rows.
        assertThat(covered).hasSizeGreaterThanOrEqualTo(100);
    }

    /** The request with every id filled from the demo project, or empty when one cannot be filled. */
    private Optional<MockHttpServletRequestBuilder> request(String pattern, HandlerMethod handler, Map<String, String> ids) {
        StringBuilder path = new StringBuilder();
        Matcher matcher = VARIABLE.matcher(pattern);
        int at = 0;
        boolean hasProjectData = false;
        while (matcher.find()) {
            Optional<String> id = id(matcher.group(1), pattern, ids);
            if (id.isEmpty()) return Optional.empty();
            path.append(pattern, at, matcher.start()).append(id.get());
            at = matcher.end();
            hasProjectData = true;
        }
        path.append(pattern.substring(at));
        MockHttpServletRequestBuilder request = get(path.toString());
        for (Parameter parameter : handler.getMethod().getParameters()) {
            RequestParam param = parameter.getAnnotation(RequestParam.class);
            if (param == null || parameter.getAnnotation(PathVariable.class) != null) continue;
            String name = !param.name().isEmpty() ? param.name() : !param.value().isEmpty() ? param.value() : parameter.getName();
            Optional<String> id = name.endsWith("Id") ? id(name, pattern, ids) : Optional.empty();
            if (id.isPresent()) {
                request.param(name, id.get());
                hasProjectData = true;
            } else if (param.required() && ValueConstants.DEFAULT_NONE.equals(param.defaultValue())) {
                // A required value that is not an id (a date, a type): this sweep cannot guess it.
                return Optional.empty();
            }
        }
        return hasProjectData ? Optional.of(request) : Optional.empty();
    }

    /**
     * A row of another project for an id named like {@code workOrderId}: the table whose primary key is {@code work_order_id}
     * (the path decides between tables that share the name), never from the outsider's own project.
     */
    private Optional<String> id(String name, String pattern, Map<String, String> ids) {
        String[] source = switch (name) {
            case "projectId" -> new String[] {null, DEMO_PROJECT};
            case "workflowId" -> new String[] {null, DEMO_WORKFLOW};
            case "processId" -> new String[] {null, "prc_demo_input"};
            case "processIoId", "ioId" -> new String[] {null, "pio_demo_input_out"};
            case "connectionId" -> new String[] {null, "pcn_demo_input_to_mix"};
            case "itemId" -> new String[] {null, DEMO_ITEM};
            case "userId" -> new String[] {null, DEMO_OWNER};
            case "bomId" -> new String[] {"bom_header", "bom_id"};
            case "lotId" -> new String[] {"lot_master", "lot_id"};
            case "ruleId" -> new String[] {"flow_rule", "rule_id"};
            case "revisionId" -> new String[] {"workflow_revision", "workflow_revision_id"};
            case "profileId" -> new String[] {"connector_profile", "profile_id"};
            case "jobId" -> new String[] {"integration_job", "job_id"};
            case "instructionId" -> new String[] {"work_instruction", "instruction_id"};
            case "planId" -> pattern.startsWith("/inventory-count-plans") ? new String[] {"inventory_count_plan", "plan_id"} : null;
            case "scheduleId" -> pattern.startsWith("/projects/") ? new String[] {"worker_schedule", "schedule_id"} : null;
            default -> guess(name);
        };
        if (source == null) return Optional.empty();
        if (source[0] == null) return Optional.of(source[1]);
        String key = source[0] + "." + source[1];
        if (!ids.containsKey(key)) ids.put(key, row(source[0], source[1]));
        return Optional.ofNullable(ids.get(key));
    }

    private static String[] guess(String name) {
        if (!name.endsWith("Id") || name.length() < 3) return null;
        String column = name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
        return new String[] {column.substring(0, column.length() - 3), column};
    }

    /** One id from the table that belongs to a project other than the outsider's (by its own project or its workflow's). */
    private String row(String table, String column) {
        List<String> columns = jdbc.queryForList("select column_name from information_schema.columns where table_schema='public' "
            + "and table_name=? and column_name in (?, 'project_id', 'workflow_id')", String.class, table, column);
        if (!columns.contains(column)) return null;
        String sql;
        if (columns.contains("project_id")) {
            sql = "select t." + column + "::text from " + table + " t where t.project_id is not null and t.project_id <> ? order by 1 limit 1";
        } else if (columns.contains("workflow_id")) {
            sql = "select t." + column + "::text from " + table + " t join workflow w on w.workflow_id = t.workflow_id "
                + "where w.project_id <> ? order by 1 limit 1";
        } else {
            return null;
        }
        return jdbc.queryForList(sql, String.class, otherProject).stream().findFirst().orElse(null);
    }

    private String bearer(String userId) {
        return "Bearer " + jwtProvider.generateAccessToken(userId);
    }
}
