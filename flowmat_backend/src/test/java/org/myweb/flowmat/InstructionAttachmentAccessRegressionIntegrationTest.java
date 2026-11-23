package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Attachment permissions and integrity are enforced at the HTTP boundary without creating BOMs. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class InstructionAttachmentAccessRegressionIntegrationTest extends IntegrationTestSupport {
    private static final Path ROOT = root();
    private static final byte[] ORIGINAL = "Private attachment content".getBytes(StandardCharsets.UTF_8);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired JwtProvider jwt;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("app.storage.type", () -> "local");
        registry.add("app.storage.upload-dir", ROOT::toString);
        registry.add("app.storage.max-file-size-bytes", () -> 1024);
    }

    @ParameterizedTest
    @ValueSource(strings = {"editor", "viewer"})
    void aRemovedMemberCannotListReadCreateOrDeleteAttachments(String role) throws Exception {
        String project = project();
        String instruction = draft(project);
        String member = member(project, role);
        String id = UUID.randomUUID().toString();
        upload(DEMO_OWNER, instruction, id, "Guide.txt").andExpect(status().isOk());
        call(member, get(base(instruction))).andExpect(status().isOk());
        assertArrayEquals(ORIGINAL, call(member, get(download(instruction, id))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray());
        long filesBefore = fileCount();
        jdbc.update("update project_member set member_status='removed' where project_id=? and user_id=?", project, member);

        call(member, get(base(instruction))).andExpect(status().isForbidden());
        call(member, get(download(instruction, id))).andExpect(status().isForbidden());
        call(member, delete(base(instruction) + "/" + id)).andExpect(status().isForbidden());
        upload(member, instruction, UUID.randomUUID().toString(), "Blocked.txt").andExpect(status().isForbidden());
        assertEquals(filesBefore, fileCount());
        assertEquals(1, attachmentCount(instruction));
        assertEquals("N", jdbc.queryForObject("select deleted_yn from instruction_attachment where attachment_id=?", String.class, id));
        assertArrayEquals(ORIGINAL, call(DEMO_OWNER, get(download(instruction, id))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray());
    }

    @Test
    void anAttachmentIdCannotBeUsedThroughAnotherInstruction() throws Exception {
        String project = project();
        String source = draft(project);
        String other = draft(project);
        String id = UUID.randomUUID().toString();
        upload(DEMO_OWNER, source, id, "Guide.txt").andExpect(status().isOk());
        long filesBefore = fileCount();
        call(DEMO_OWNER, get(download(other, id))).andExpect(status().isNotFound());
        call(DEMO_OWNER, delete(base(other) + "/" + id)).andExpect(status().isNotFound());
        upload(DEMO_OWNER, other, id, "Guide.txt").andExpect(status().isConflict());
        assertEquals(0, attachmentCount(other));
        assertEquals(filesBefore, fileCount());
        assertArrayEquals(ORIGINAL, call(DEMO_OWNER, get(download(source, id))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void corruptStoredContentIsRefusedWithoutReturningBytesOrStorageDetails(boolean changeSize) throws Exception {
        String instruction = draft(project());
        String id = UUID.randomUUID().toString();
        upload(DEMO_OWNER, instruction, id, "Guide.txt").andExpect(status().isOk());
        String key = jdbc.queryForObject("select storage_key from instruction_attachment where attachment_id=?", String.class, id);
        Path target = ROOT.resolve(key).normalize();
        assertTrue(target.startsWith(ROOT));
        byte[] damaged = Arrays.copyOf(ORIGINAL, ORIGINAL.length + (changeSize ? 1 : 0));
        damaged[0] = 'X';
        Files.write(target, damaged);
        var response = call(DEMO_OWNER, get(download(instruction, id))).andExpect(status().isInternalServerError())
            .andReturn().getResponse();
        JsonNode envelope = json.readTree(response.getContentAsString());
        assertFalse(envelope.path("success").asBoolean());
        assertTrue(envelope.path("data").isNull());
        assertEquals("Stored attachment is unavailable. Retry the download or contact the project owner.",
            envelope.path("message").asText());
        assertFalse(response.getContentAsString().contains(key));
        assertFalse(response.getContentAsString().contains(ROOT.toString()));
        assertFalse(response.getContentAsString().contains(new String(damaged, StandardCharsets.UTF_8)));
        assertNull(response.getHeader(HttpHeaders.CONTENT_DISPOSITION));
        // The attachment metadata remains usable if storage is restored.
        Files.write(target, ORIGINAL);
        assertArrayEquals(ORIGINAL, call(DEMO_OWNER, get(download(instruction, id))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray());
    }

    @Test
    void downloadNamesKeepQuotesAndUnicodeWithoutLeakingClientPaths() throws Exception {
        String instruction = draft(project());
        String id = UUID.randomUUID().toString();
        String basename = "Guide \"quoted\" 이름.txt";
        JsonNode uploaded = data(upload(DEMO_OWNER, instruction, id, "..\\..\\" + basename));
        assertEquals(basename, uploaded.path("fileName").asText());
        assertFalse(uploaded.has("storageKey"));
        var response = call(DEMO_OWNER, get(download(instruction, id))).andExpect(status().isOk())
            .andReturn().getResponse();
        ContentDisposition disposition = ContentDisposition.parse(response.getHeader(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals("attachment", disposition.getType());
        assertEquals(basename, disposition.getFilename());
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("private, no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
        assertEquals(MediaType.TEXT_PLAIN_VALUE, response.getContentType());
        assertArrayEquals(ORIGINAL, response.getContentAsByteArray());
    }

    @Test
    void deletingTheParentInstructionBlocksAllAttachmentEndpoints() throws Exception {
        String instruction = draft(project());
        String id = UUID.randomUUID().toString();
        upload(DEMO_OWNER, instruction, id, "Guide.txt").andExpect(status().isOk());
        long filesBefore = fileCount();
        jdbc.update("update work_instruction set deleted_yn='Y' where instruction_id=?", instruction);
        call(DEMO_OWNER, get(base(instruction))).andExpect(status().isNotFound());
        call(DEMO_OWNER, get(download(instruction, id))).andExpect(status().isNotFound());
        call(DEMO_OWNER, delete(base(instruction) + "/" + id)).andExpect(status().isNotFound());
        upload(DEMO_OWNER, instruction, UUID.randomUUID().toString(), "Blocked.txt").andExpect(status().isNotFound());
        assertEquals(filesBefore, fileCount());
        assertEquals(1, attachmentCount(instruction));
    }

    private String project() {
        String project = UUID.randomUUID().toString();
        jdbc.update("insert into project(project_id,project_name,owner_id,organization_id) "
            + "select ?,?,owner_id,organization_id from project where project_id=?", project, "Attachment security fixture", DEMO_PROJECT);
        return project;
    }

    private String member(String project, String role) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role,member_status) "
            + "values(?,?,?,?,'active')", UUID.randomUUID().toString(), project, id, role);
        return id;
    }

    private String draft(String project) throws Exception {
        String item = data(call(DEMO_OWNER, post("/items").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
            Map.of("projectId", project, "itemCode", "SEC-" + UUID.randomUUID(), "itemName", "Guard", "unitId", "unit_ea")))))
            .path("itemId").asText();
        return data(call(DEMO_OWNER, post("/work-instructions").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
            Map.of("projectId", project, "itemId", item, "title", "Attachment security guard")))))
            .path("instructionId").asText();
    }

    private ResultActions upload(String actor, String instruction, String id, String filename) throws Exception {
        return call(actor, multipart(base(instruction) + "/" + id)
            .file(new MockMultipartFile("file", filename, MediaType.TEXT_PLAIN_VALUE, ORIGINAL))
            .with(request -> { request.setMethod("PUT"); return request; }));
    }

    private ResultActions call(String actor, AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.generateAccessToken(actor)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private int attachmentCount(String instruction) {
        return jdbc.queryForObject("select count(*) from instruction_attachment where instruction_id=?", Integer.class, instruction);
    }

    private long fileCount() throws IOException {
        try (var paths = Files.walk(ROOT)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private static String base(String instruction) { return "/work-instructions/" + instruction + "/attachments"; }
    private static String download(String instruction, String id) { return base(instruction) + "/" + id + "/download"; }

    private static Path root() {
        try {
            return Files.createTempDirectory("flowmat-attachment-access-").toAbsolutePath().normalize();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @AfterAll
    static void removeOwnTemporaryStorage() throws IOException {
        // ROOT was created by this test; validate every resolved path before deleting anything.
        List<Path> owned;
        try (var paths = Files.walk(ROOT)) {
            owned = paths.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : owned) assertTrue(path.toAbsolutePath().normalize().startsWith(ROOT));
        for (Path path : owned) Files.deleteIfExists(path);
    }
}
