package org.myweb.flowmat.domain.connector.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConnectorHttpTransportTest {
    private HttpServer server;
    private ExecutorService serverWorkers;
    private ConnectorHttpTransport transport;

    @BeforeEach
    void startOwnServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        serverWorkers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverWorkers);
        server.start();
        transport = new ConnectorHttpTransport(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(3), 2);
    }

    @AfterEach
    void stopOwnResources() {
        transport.close();
        server.stop(0);
        serverWorkers.shutdownNow();
    }

    @Test
    void pinsOneResolvedAddressWhilePreservingHostAndJsonBody() {
        AtomicReference<String> host = new AtomicReference<>();
        AtomicReference<String> received = new AtomicReference<>();
        AtomicInteger resolutions = new AtomicInteger();
        server.createContext("/json", exchange -> {
            host.set(exchange.getRequestHeaders().getFirst("Host"));
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"value\":1.2500}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        var request = request("/json", () -> {
            resolutions.incrementAndGet();
            return InetAddress.getLoopbackAddress();
        });

        var result = transport.exchange(request);

        assertThat(result.status()).isEqualTo(201);
        assertThat(new String(result.body(), StandardCharsets.UTF_8)).isEqualTo("{\"value\":1.2500}");
        assertThat(result.contentType()).isEqualTo("application/json");
        assertThat(host.get()).isEqualTo("localhost:" + server.getAddress().getPort());
        assertThat(received.get()).isEqualTo("{\"input\":true}");
        assertThat(resolutions).hasValue(1);
    }

    @Test
    void doesNotFollowRedirectOrRetryServerFailure() {
        AtomicInteger redirects = new AtomicInteger();
        AtomicInteger destinations = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            redirects.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "/destination");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/destination", exchange -> {
            destinations.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/failure", exchange -> {
            failures.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "0");
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });

        assertThat(transport.exchange(request("/redirect", InetAddress::getLoopbackAddress)).status()).isEqualTo(307);
        assertThat(transport.exchange(request("/failure", InetAddress::getLoopbackAddress)).status()).isEqualTo(503);
        assertThat(redirects).hasValue(1);
        assertThat(destinations).hasValue(0);
        assertThat(failures).hasValue(1);
    }

    @Test
    void decodesBoundedGzipAndRejectsExpandedOverflow() throws Exception {
        byte[] small = gzip("{\"accepted\":true}".getBytes(StandardCharsets.UTF_8));
        byte[] huge = gzip(new byte[131073]);
        server.createContext("/gzip", exchange -> {
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, small.length);
            exchange.getResponseBody().write(small);
            exchange.close();
        });
        server.createContext("/bomb", exchange -> {
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, huge.length);
            exchange.getResponseBody().write(huge);
            exchange.close();
        });

        assertThat(new String(transport.exchange(request("/gzip", InetAddress::getLoopbackAddress)).body(),
            StandardCharsets.UTF_8)).isEqualTo("{\"accepted\":true}");
        assertThatThrownBy(() -> transport.exchange(request("/bomb", InetAddress::getLoopbackAddress)))
            .isInstanceOfSatisfying(ConnectorHttpTransport.TransportFailure.class, failure -> {
                assertThat(failure.code()).isEqualTo("RESPONSE_TOO_LARGE");
                assertThat(failure.mayHaveBeenSent()).isTrue();
                assertThat(failure.httpStatus()).isEqualTo(200);
            });
    }

    @Test
    void rejectsRawResponseOverflowWithoutReturningRemoteBytes() {
        server.createContext("/large", exchange -> {
            exchange.sendResponseHeaders(200, 131073);
            exchange.getResponseBody().write(new byte[131073]);
            exchange.close();
        });
        assertThatThrownBy(() -> transport.exchange(request("/large", InetAddress::getLoopbackAddress)))
            .isInstanceOfSatisfying(ConnectorHttpTransport.TransportFailure.class, failure -> {
                assertThat(failure.code()).isEqualTo("RESPONSE_TOO_LARGE");
                assertThat(failure.getCause()).isNull();
            });
    }

    @Test
    void rejectsExcessiveResponseHeaders() {
        server.createContext("/headers", exchange -> {
            for (int index = 0; index < 66; index++) exchange.getResponseHeaders().add("X-" + index, "value");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        assertThatThrownBy(() -> transport.exchange(request("/headers", InetAddress::getLoopbackAddress)))
            .isInstanceOf(ConnectorHttpTransport.TransportFailure.class);
    }

    @Test
    void totalDeadlineStopsTrickledResponseEvenWhileReadTimeoutHasNotElapsed() {
        transport.close();
        transport = new ConnectorHttpTransport(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(150), 1);
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try {
                for (int index = 0; index < 100; index++) {
                    exchange.getResponseBody().write(' ');
                    exchange.getResponseBody().flush();
                    Thread.sleep(40);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        long started = System.nanoTime();
        assertThatThrownBy(() -> transport.exchange(request("/slow", InetAddress::getLoopbackAddress)))
            .isInstanceOfSatisfying(ConnectorHttpTransport.TransportFailure.class,
                failure -> assertThat(failure.code()).isEqualTo("TOTAL_TIMEOUT"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void dnsCompletingAfterDeadlineCannotSendAndDoesNotCreateUnboundedWorkers() throws Exception {
        transport.close();
        transport = new ConnectorHttpTransport(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(100), 1);
        CountDownLatch dnsStarted = new CountDownLatch(1);
        CountDownLatch releaseDns = new CountDownLatch(1);
        CountDownLatch dnsFinished = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/late", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        try {
            assertThatThrownBy(() -> transport.exchange(request("/late", () -> {
                dnsStarted.countDown();
                boolean done = false;
                while (!done) {
                    try {
                        releaseDns.await();
                        done = true;
                    } catch (InterruptedException ignored) {
                        // Model the platform DNS resolver, which need not honor interruption.
                    }
                }
                dnsFinished.countDown();
                return InetAddress.getLoopbackAddress();
            }))).isInstanceOf(ConnectorHttpTransport.TransportFailure.class);
            assertThat(dnsStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> transport.exchange(request("/late", InetAddress::getLoopbackAddress)))
                .isInstanceOfSatisfying(ConnectorHttpTransport.TransportFailure.class,
                    failure -> assertThat(failure.code()).isEqualTo("BUSY"));
        } finally {
            releaseDns.countDown();
        }
        assertThat(dnsFinished.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(requests).hasValue(0);
    }

    @Test
    void requestAndResponseStringRepresentationsContainNoBodyOrCredentials() {
        var request = new ConnectorHttpTransport.TransportRequest(URI.create("https://example.test/private"), "POST",
            Map.of("Authorization", "Bearer private-credential"), "secret-body".getBytes(StandardCharsets.UTF_8),
            InetAddress::getLoopbackAddress);
        var response = new ConnectorHttpTransport.TransportResponse(200,
            "secret-response".getBytes(StandardCharsets.UTF_8), "application/json");
        assertThat(request.toString()).doesNotContain("private-credential", "secret-body", "/private");
        assertThat(response.toString()).doesNotContain("secret-response");
    }

    private ConnectorHttpTransport.TransportRequest request(String path, Supplier<InetAddress> resolver) {
        return new ConnectorHttpTransport.TransportRequest(
            URI.create("http://localhost:" + server.getAddress().getPort() + path), "POST", Map.of(),
            "{\"input\":true}".getBytes(StandardCharsets.UTF_8), resolver);
    }

    private static byte[] gzip(byte[] bytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(bytes);
        }
        return output.toByteArray();
    }
}
