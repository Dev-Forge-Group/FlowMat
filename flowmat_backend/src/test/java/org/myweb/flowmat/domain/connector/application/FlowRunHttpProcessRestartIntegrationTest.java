package org.myweb.flowmat.domain.connector.application;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.myweb.flowmat.Application;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorHttpGateway;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Two real application JVMs share only an owned database and Redis. The gateway deliberately blocks
 * after the real API commits its claim; external transport is covered by separate real-socket tests.
 * No production target policy is replaced or relaxed, and this test never sends to a provider.
 */
class FlowRunHttpProcessRestartIntegrationTest {
    private static final String PROJECT = "prj_demo_main";
    private static final String ORIGIN = "https://api.example.com";
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test
    @Timeout(value = 360, unit = TimeUnit.SECONDS)
    void committedPendingClaimSurvivesAnActualProcessCrashAndBlocksAnotherSend() throws Exception {
        Path workspace = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "flowmat-http-restart-");
        String secret = UUID.randomUUID().toString() + UUID.randomUUID();
        String token = new JwtProvider(secret, 600, 600).generateAccessToken("demo-owner");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
             GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379)) {
            postgres.start();
            redis.start();
            JdbcTemplate database = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            OwnedApplication first = null;
            OwnedApplication second = null;
            CompletableFuture<HttpResponse<String>> interruptedRequest = null;
            boolean finished = false;
            try {
                first = launch(workspace, "first", postgres, redis, secret);
                URI firstApi = first.api();
                long initialBoms = database.queryForObject("select count(*) from bom_header", Long.class);
                String workflow = data(firstApi, token, "POST", "/workflows", json.writeValueAsString(Map.of(
                    "projectId", PROJECT, "workflowName", "Pending crash " + UUID.randomUUID())))
                    .path("workflowId").asText();
                String process = data(firstApi, token, "POST", "/processes", json.writeValueAsString(Map.of(
                    "workflowId", workflow, "processName", "Blocked HTTP"))).path("processId").asText();
                String output = data(firstApi, token, "POST", "/process-ios", json.writeValueAsString(Map.of(
                    "processId", process, "direction", "output", "resourceType", "data", "ioName", "Response", "requiredYn", "N")))
                    .path("processIoId").asText();
                String profileVersion = data(firstApi, token, "POST", "/projects/" + PROJECT + "/http-profiles",
                    "{\"name\":\"Crash acceptance\",\"baseOrigin\":\"" + ORIGIN
                        + "\",\"enabled\":true,\"operations\":[{\"operationId\":\"send\",\"method\":\"POST\",\"relativePath\":\"/send\",\"successStatuses\":[200]}]}")
                    .path("profileVersionId").asText();
                data(firstApi, token, "PUT", "/processes/" + process + "/execution-binding",
                    "{\"expectedVersion\":0,\"profileVersionId\":\"" + profileVersion
                        + "\",\"operationId\":\"send\",\"outputPortId\":\"" + output
                        + "\",\"requestMapping\":{\"from\":\"\"},\"responseMapping\":{\"from\":\"\"}}");
                String revision = data(firstApi, token, "POST", "/workflows/" + workflow + "/revisions", null)
                    .path("workflowRevisionId").asText();
                String run = data(firstApi, token, "POST", "/flow-runs/graph", "{\"workflowId\":\"" + workflow
                    + "\",\"workflowRevisionId\":\"" + revision + "\",\"runType\":\"actual\",\"inputPayload\":{\"value\":1}}")
                    .path("flowRunId").asText();
                String step = data(firstApi, token, "GET", "/flow-runs/" + run + "/steps", null)
                    .get(0).path("stepId").asText();
                String endpoint = "/flow-runs/" + run + "/steps/" + step;
                String requestId = UUID.randomUUID().toString();
                String body = "{\"requestId\":\"" + requestId + "\"}";
                interruptedRequest = http.sendAsync(request(firstApi, token, "POST", endpoint + "/execute", body),
                    HttpResponse.BodyHandlers.ofString());
                Path firstGateway = first.gatewayMarker();
                await(() -> Files.exists(firstGateway), Duration.ofSeconds(20), "First gateway was never entered.");
                assertEquals(1L, database.queryForObject(
                    "select count(*) from flow_run_http_execution where step_id=? and request_id=? and transport_status='PENDING' and apply_status='PENDING'",
                    Long.class, step, requestId));
                String execution = database.queryForObject(
                    "select execution_id from flow_run_http_execution where step_id=?", String.class, step);
                String attempt = database.queryForObject(
                    "select attempt_id from flow_run_http_execution where execution_id=?", String.class, execution);
                assertEquals("running", database.queryForObject("select status from flow_run_step where step_id=?", String.class, step));
                assertEquals("running", database.queryForObject("select status from flow_run_step_attempt where attempt_id=?", String.class, attempt));
                assertEquals(List.of("claimed"), Files.readAllLines(firstGateway));

                long firstPid = first.pid();
                Instant firstStarted = first.started();
                first.crash();
                assertFalse(ProcessHandle.of(firstPid).filter(ProcessHandle::isAlive).isPresent());
                interruptedRequest.handle((ignored, failure) -> null).get(10, TimeUnit.SECONDS);

                second = launch(workspace, "second", postgres, redis, secret);
                assertNotEquals(firstPid, second.pid(), "The restoration must use a different operating-system process.");
                assertTrue(second.started().isAfter(firstStarted));
                URI restoredApi = second.api();
                JsonNode stored = data(restoredApi, token, "GET", endpoint + "/http-executions", null);
                assertEquals(1, stored.size());
                assertEquals(execution, stored.get(0).path("executionId").asText());
                assertEquals(requestId, stored.get(0).path("requestId").asText());
                assertEquals(attempt, stored.get(0).path("attemptId").asText());
                assertPending(stored.get(0));
                assertPending(data(restoredApi, token, "POST", endpoint + "/execute", body));
                assertEquals(409, send(restoredApi, token, "POST", endpoint + "/execute",
                    "{\"requestId\":\"" + UUID.randomUUID() + "\"}").statusCode());
                assertEquals(409, send(restoredApi, token, "POST", endpoint + "/http-executions/" + execution + "/apply", null).statusCode());
                assertFalse(Files.exists(second.gatewayMarker()), "A restored pending claim must not enter the gateway again.");
                assertEquals(1L, database.queryForObject("select count(*) from flow_run_http_execution where step_id=?", Long.class, step));
                assertEquals(1L, database.queryForObject("select count(*) from flow_run_step_attempt where step_id=?", Long.class, step));
                assertEquals(initialBoms, database.queryForObject("select count(*) from bom_header", Long.class));
                assertEquals("running", database.queryForObject("select status from flow_run_step where step_id=?", String.class, step));
                finished = true;
            } finally {
                try {
                    if (second != null) second.close();
                } finally {
                    try {
                        if (first != null) first.close();
                    } finally {
                        if (interruptedRequest != null) interruptedRequest.cancel(true);
                        // A failing run keeps its owned logs for the message above; a passing one leaves nothing behind (audit B8).
                        if (finished) deleteQuietly(workspace);
                    }
                }
            }
        }
    }

    private void assertPending(JsonNode value) {
        assertEquals("PENDING", value.path("transportStatus").asText());
        assertEquals("PENDING", value.path("applyStatus").asText());
        assertTrue(value.path("completedAt").isNull());
        assertTrue(value.path("output").isNull());
    }

    private JsonNode data(URI api, String token, String method, String path, String body) throws Exception {
        HttpResponse<String> response = send(api, token, method, path, body);
        assertEquals(200, response.statusCode(), method + " " + path + " failed.");
        JsonNode envelope = json.readTree(response.body());
        assertTrue(envelope.path("success").asBoolean(), "API did not return a successful envelope.");
        return envelope.path("data");
    }

    private HttpResponse<String> send(URI api, String token, String method, String path, String body) throws Exception {
        return http.send(request(api, token, method, path, body), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(URI api, String token, String method, String path, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(api + path))
            .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private static OwnedApplication launch(Path workspace, String name, PostgreSQLContainer<?> postgres,
        GenericContainer<?> redis, String secret) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toRealPath();
        Path argsFile = workspace.resolve(name + ".args");
        Path ready = workspace.resolve(name + ".ready");
        Path gateway = workspace.resolve(name + ".gateway");
        List<String> args = new ArrayList<>(List.of("-Xmx384m", "-Dspring.devtools.restart.enabled=false",
            "-Djava.io.tmpdir=" + workspace.toAbsolutePath(), "-Drestart.acceptance.parent=" + ProcessHandle.current().pid(), "-cp", childClasspath(), ChildApplication.class.getName(),
            "--spring.profiles.active=dev", "--server.address=127.0.0.1", "--server.port=0", "--spring.jpa.hibernate.ddl-auto=validate",
            "--flowmat.connector.enabled=true", "--flowmat.connector.allowed-origins=" + ORIGIN,
            "--app.storage.upload-dir=" + workspace.resolve("uploads"), "--logging.level.root=WARN",
            "--logging.level." + ChildApplication.class.getName() + "=INFO",
            "--logging.level.com.zaxxer.hikari=INFO", "--logging.level.org.flywaydb=INFO",
            "--logging.level.org.springframework.boot.web.embedded.tomcat=INFO",
            "--logging.level.org.hibernate.SQL=OFF", "--restart.acceptance.ready=" + ready,
            "--restart.acceptance.gateway=" + gateway));
        Files.writeString(argsFile, args.stream().map(FlowRunHttpProcessRestartIntegrationTest::quoteArgument)
            .collect(Collectors.joining(System.lineSeparator())), StandardCharsets.UTF_8);
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "@" + argsFile.toAbsolutePath());
        builder.directory(workspace.toFile()).redirectErrorStream(true).redirectOutput(workspace.resolve(name + ".log").toFile());
        Map<String, String> env = builder.environment();
        // A test child must never inherit external Spring config, database URLs or JVM options.
        env.clear();
        for (String key : List.of("SystemRoot", "WINDIR", "PATH", "Path", "TEMP", "TMP")) {
            String value = System.getenv(key);
            if (value != null) env.put(key, value);
        }
        env.put("DB_URL", postgres.getJdbcUrl());
        env.put("DB_USERNAME", postgres.getUsername());
        env.put("DB_PASSWORD", postgres.getPassword());
        env.put("SPRING_DATASOURCE_URL", postgres.getJdbcUrl());
        env.put("SPRING_DATASOURCE_USERNAME", postgres.getUsername());
        env.put("SPRING_DATASOURCE_PASSWORD", postgres.getPassword());
        env.put("SPRING_FLYWAY_URL", postgres.getJdbcUrl());
        env.put("SPRING_FLYWAY_USER", postgres.getUsername());
        env.put("SPRING_FLYWAY_PASSWORD", postgres.getPassword());
        env.put("REDIS_HOST", redis.getHost());
        env.put("REDIS_PORT", redis.getMappedPort(6379).toString());
        env.put("SPRING_DATA_REDIS_HOST", redis.getHost());
        env.put("SPRING_DATA_REDIS_PORT", redis.getMappedPort(6379).toString());
        env.put("JWT_SECRET", secret);
        env.put("MAIL_HOST", "127.0.0.1");
        env.put("MAIL_PORT", "2525");
        env.put("MAIL_USERNAME", "");
        env.put("MAIL_PASSWORD", "");
        env.put("FRONTEND_URL", "http://localhost:5173");
        env.put("APP_OAUTH2_REDIRECT_URI", "http://localhost:5173/oauth/callback");
        env.put("APP_CORS_ALLOWED_ORIGINS", "http://localhost:5173");
        Process process = builder.start();
        OwnedApplication app = new OwnedApplication(process, java, argsFile, ready, gateway);
        try {
            await(() -> Files.exists(ready) || !process.isAlive(), Duration.ofSeconds(120),
                "Child " + name + " (PID " + app.pid() + ") did not become ready; inspect its owned log under " + workspace);
            assertTrue(process.isAlive(), "Child application exited before readiness; inspect its owned log under " + workspace);
            String[] identity = Files.readString(ready).trim().split("\\n");
            assertEquals(app.pid(), Long.parseLong(identity[1]));
            assertEquals(app.started(), Instant.parse(identity[2]));
            app.port = Integer.parseInt(identity[0]);
            return app;
        } catch (Exception | AssertionError failure) {
            app.close();
            throw failure;
        }
    }

    private static String childClasspath() throws Exception {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        for (ClassLoader loader = FlowRunHttpProcessRestartIntegrationTest.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs()) if (url.getProtocol().equals("file")) entries.add(Path.of(url.toURI()).toAbsolutePath().toString());
            }
        }
        // Gradle can use a non-URL worker class loader; discover its actual runtime resources as well.
        for (Class<?> type : List.of(FlowRunHttpProcessRestartIntegrationTest.class, Application.class, SpringApplication.class)) {
            entries.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().toString());
        }
        var roots = FlowRunHttpProcessRestartIntegrationTest.class.getClassLoader().getResources("");
        while (roots.hasMoreElements()) {
            var root = roots.nextElement();
            if (root.getProtocol().equals("file")) entries.add(Path.of(root.toURI()).toAbsolutePath().toString());
        }
        var manifests = FlowRunHttpProcessRestartIntegrationTest.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
        while (manifests.hasMoreElements()) {
            var manifest = manifests.nextElement();
            if (manifest.openConnection() instanceof java.net.JarURLConnection jar) {
                entries.add(Path.of(jar.getJarFileURL().toURI()).toAbsolutePath().toString());
            }
        }
        entries.addAll(Arrays.asList(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator))));
        assertTrue(entries.stream().anyMatch(entry -> entry.replace('\\', '/').endsWith("/classes/java/test")),
            "The child must inherit Gradle's complete test runtime classpath.");
        return String.join(File.pathSeparator, entries);
    }

    /**
     * Best effort: a leftover folder must not fail a passing run. Windows can hold a just-stopped child's log for a moment,
     * so the delete is tried a few times.
     */
    private static void deleteQuietly(Path workspace) {
        for (int attempt = 0; attempt < 10 && Files.exists(workspace); attempt++) {
            try (var paths = Files.walk(workspace)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException | java.io.UncheckedIOException ignored) {
                // Retried below; the next run uses a new folder anyway.
            }
            if (Files.exists(workspace)) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void writeMarker(Path target, String value) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, value, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String quoteArgument(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void await(BooleanSupplier condition, Duration limit, String message) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, message);
            Thread.sleep(50);
        }
    }

    private static final class OwnedApplication implements AutoCloseable {
        private final Process process;
        private final Path java;
        private final Path argsFile;
        private final Path ready;
        private final Path gateway;
        private final Instant started;
        private int port;

        private OwnedApplication(Process process, Path java, Path argsFile, Path ready, Path gateway) {
            this.process = process;
            this.java = java;
            this.argsFile = argsFile;
            this.ready = ready;
            this.gateway = gateway;
            this.started = process.info().startInstant().orElseThrow();
        }

        long pid() { return process.pid(); }
        Instant started() { return started; }
        Path gatewayMarker() { return gateway; }
        URI api() { return URI.create("http://127.0.0.1:" + port + "/api"); }

        void crash() throws Exception {
            assertIdentity();
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Owned child JVM did not stop.");
        }

        private void assertIdentity() throws IOException, InterruptedException {
            ProcessHandle handle = ProcessHandle.of(pid()).orElseThrow();
            assertTrue(handle.isAlive());
            ProcessHandle.Info info = handle.info();
            assertEquals(started, info.startInstant().orElseThrow());
            assertTrue(Files.isSameFile(java, Path.of(info.command().orElseThrow())), "Owned executable changed.");
            String arguments = info.arguments().map(values -> String.join(" ", values))
                .orElseGet(() -> info.commandLine().orElse(""));
            if (arguments.isBlank() && System.getProperty("os.name").startsWith("Windows")) {
                // Windows may omit command-line metadata from ProcessHandle.Info.
                Process lookup = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "(Get-CimInstance Win32_Process -Filter 'ProcessId=" + pid() + "').CommandLine")
                    .redirectErrorStream(true).start();
                // PowerShell can take well over five seconds to start while a full test run loads the machine.
                if (!lookup.waitFor(30, TimeUnit.SECONDS)) {
                    lookup.destroyForcibly();
                    throw new IOException("Owned child command-line lookup timed out.");
                }
                assertEquals(0, lookup.exitValue(), "Owned child command-line lookup failed.");
                arguments = new String(lookup.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            assertTrue(arguments.contains("@" + argsFile.toAbsolutePath()),
                "Owned child launcher identity changed.");
            if (Files.exists(ready)) {
                String[] recorded = Files.readString(ready).trim().split("\\n");
                assertEquals(pid(), Long.parseLong(recorded[1]));
                assertEquals(started, Instant.parse(recorded[2]));
                if (port != 0) assertEquals(port, Integer.parseInt(recorded[0]));
            }
        }

        @Override public void close() throws Exception { if (process.isAlive()) crash(); }
    }

    /** Only explicitly launched child JVMs import this test-only configuration. */
    public static final class ChildApplication {
        public static void main(String[] args) {
            // A test JVM killed outright cannot close its children; end with it instead of holding a port and logs (audit B8).
            ProcessHandle.of(Long.getLong("restart.acceptance.parent", -1L)).ifPresentOrElse(
                parent -> parent.onExit().thenRun(() -> Runtime.getRuntime().halt(3)),
                () -> Runtime.getRuntime().halt(3));
            SpringApplication application = new SpringApplication(Application.class, BlockingGatewayConfiguration.class);
            application.setLogStartupInfo(true);
            // A plain application on the test classpath must not discover other tests' configurations.
            application.addInitializers(context -> context.getBeanFactory().registerSingleton(
                "restartAcceptanceScanExclusion", new TestClasspathExclusion()));
            application.addListeners((ApplicationListener<ApplicationReadyEvent>) event -> {
                ServletWebServerApplicationContext context = (ServletWebServerApplicationContext) event.getApplicationContext();
                Path marker = Path.of(context.getEnvironment().getRequiredProperty("restart.acceptance.ready"));
                ProcessHandle self = ProcessHandle.current();
                try {
                    writeMarker(marker, context.getWebServer().getPort() + "\n" + self.pid() + "\n"
                        + self.info().startInstant().orElseThrow() + "\n");
                } catch (IOException failure) { throw new IllegalStateException("Cannot record owned child readiness.", failure); }
            });
            application.run(args);
        }
    }

    private static final class TestClasspathExclusion extends TypeExcludeFilter {
        private final String testLocation = FlowRunHttpProcessRestartIntegrationTest.class.getProtectionDomain()
            .getCodeSource().getLocation().toExternalForm();

        @Override public boolean match(MetadataReader metadata, MetadataReaderFactory factory) throws IOException {
            return metadata.getResource().getURL().toExternalForm().startsWith(testLocation);
        }

        @Override public boolean equals(Object other) { return other != null && other.getClass() == getClass(); }
        @Override public int hashCode() { return getClass().hashCode(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BlockingGatewayConfiguration {
        @Bean
        @Primary
        ConnectorHttpGateway restartAcceptanceGateway(JdbcTemplate database, Environment environment) {
            Path marker = Path.of(environment.getRequiredProperty("restart.acceptance.gateway"));
            CountDownLatch blocker = new CountDownLatch(1);
            return (operation, request) -> {
                assertEquals(1L, database.queryForObject(
                    "select count(*) from flow_run_http_execution where profile_version_id=? and transport_status='PENDING' and apply_status='PENDING'",
                    Long.class, operation.profileVersionId()), "Gateway entry must follow the committed claim.");
                try {
                    writeMarker(marker, "claimed\n");
                    if (!blocker.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("Parent did not terminate the owned blocked application.");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Owned application was interrupted.");
                } catch (IOException failure) { throw new IllegalStateException("Cannot record test gateway entry.", failure); }
                throw new IllegalStateException("Test gateway cannot return a provider result.");
            };
        }
    }
}
