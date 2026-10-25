package org.myweb.flowmat.domain.connector.application;

import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.io.HttpClientConnectionOperator;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

/** Bounded one-shot transport. Address validation is evaluated inside the same total deadline as I/O. */
@Component
public class ConnectorHttpTransport implements AutoCloseable {
    static final int MAX_BYTES = 131072;
    private static final int MAX_HEADERS = 64;
    private static final int MAX_HEADER_BYTES = 16384;
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Duration totalTimeout;
    private final ThreadPoolExecutor workers;

    public ConnectorHttpTransport() {
        this(Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(15), 4);
    }

    ConnectorHttpTransport(Duration connectTimeout, Duration readTimeout, Duration totalTimeout, int maximumWorkers) {
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.totalTimeout = totalTimeout;
        workers = new ThreadPoolExecutor(maximumWorkers, maximumWorkers, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("connector-http-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
    }

    public TransportResponse exchange(TransportRequest request) {
        if (request == null || !METHODS.contains(request.method()) || request.target() == null
            || request.address() == null || request.body() != null && request.body().length > MAX_BYTES) {
            throw new TransportFailure("INVALID_REQUEST", false, null);
        }
        HttpUriRequestBase outgoing = new HttpUriRequestBase(request.method(), request.target());
        AtomicBoolean mayHaveBeenSent = new AtomicBoolean();
        Future<TransportResponse> pending;
        try {
            pending = workers.submit(() -> perform(request, outgoing, mayHaveBeenSent));
        } catch (RejectedExecutionException exception) {
            throw new TransportFailure("BUSY", false, null);
        }
        try {
            return pending.get(totalTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            outgoing.cancel();
            pending.cancel(true);
            throw new TransportFailure("TOTAL_TIMEOUT", mayHaveBeenSent.get(), null);
        } catch (InterruptedException exception) {
            outgoing.cancel();
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new TransportFailure("CANCELLED", mayHaveBeenSent.get(), null);
        } catch (ExecutionException exception) {
            outgoing.cancel();
            if (exception.getCause() instanceof TransportFailure failure) throw failure;
            throw new TransportFailure("POLICY_REJECTED", mayHaveBeenSent.get(), null);
        }
    }

    private TransportResponse perform(TransportRequest request, HttpUriRequestBase outgoing,
                                      AtomicBoolean mayHaveBeenSent) {
        InetAddress address = request.address().get();
        if (address == null || outgoing.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new TransportFailure("CANCELLED", false, null);
        }
        DnsResolver pinned = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                if (!request.target().getHost().equalsIgnoreCase(host)) throw new java.net.UnknownHostException();
                return new InetAddress[] {InetAddress.getByAddress(address.getAddress())};
            }

            @Override
            public String resolveCanonicalHostname(String host) throws java.net.UnknownHostException {
                if (!request.target().getHost().equalsIgnoreCase(host)) throw new java.net.UnknownHostException();
                return request.target().getHost();
            }
        };
        var factory = ManagedHttpClientConnectionFactory.builder().http1Config(Http1Config.custom()
            .setMaxHeaderCount(MAX_HEADERS).setMaxLineLength(8192).setMaxEmptyLineCount(2).build()).build();
        TlsSocketStrategy tls = ClientTlsStrategyBuilder.create().setSslContext(SSLContexts.createSystemDefault())
            .setTlsVersions("TLSv1.3", "TLSv1.2").buildClassic();
        var manager = new PoolingHttpClientConnectionManagerBuilder() {
            @Override
            protected HttpClientConnectionOperator createConnectionOperator(SchemePortResolver schemeResolver,
                    DnsResolver dnsResolver, TlsSocketStrategy tlsStrategy) {
                // Explicit NO_PROXY also suppresses the JVM's global SOCKS proxy selection.
                return new DefaultHttpClientConnectionOperator(proxy -> new Socket(Proxy.NO_PROXY),
                    schemeResolver, dnsResolver,
                    RegistryBuilder.<TlsSocketStrategy>create().register("https", tlsStrategy).build());
            }
        }.setConnectionFactory(factory).setDnsResolver(pinned).setTlsSocketStrategy(tls)
            .setMaxConnTotal(1).setMaxConnPerRoute(1)
            .setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(timeout(connectTimeout)).setSocketTimeout(timeout(readTimeout)).build())
            .setDefaultSocketConfig(SocketConfig.custom().setSoTimeout(timeout(readTimeout)).build()).build();
        RequestConfig config = RequestConfig.custom().setAuthenticationEnabled(false).setRedirectsEnabled(false)
            .setHardCancellationEnabled(true).setProtocolUpgradeEnabled(false).setExpectContinueEnabled(false)
            .setConnectionRequestTimeout(timeout(connectTimeout)).setResponseTimeout(timeout(readTimeout)).build();
        CloseableHttpClient client = HttpClients.custom().setConnectionManager(manager)
            .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
            .setDefaultRequestConfig(config).disableAutomaticRetries().disableRedirectHandling()
            .disableCookieManagement().disableAuthCaching().disableContentCompression().disableConnectionState().build();
        Integer status = null;
        try {
            if (outgoing.isCancelled() || Thread.currentThread().isInterrupted()) {
                throw new TransportFailure("CANCELLED", false, null);
            }
            for (var header : request.headers().entrySet()) outgoing.setHeader(header.getKey(), header.getValue());
            outgoing.setHeader("Accept", "application/json");
            outgoing.setHeader("Accept-Encoding", "gzip, identity");
            if (request.body() != null) outgoing.setEntity(new ByteArrayEntity(request.body(), ContentType.APPLICATION_JSON));
            mayHaveBeenSent.set(true);
            ClassicHttpResponse response = client.executeOpen(null, outgoing, null);
            try {
                status = response.getCode();
                requireHeaders(response, status);
                String contentType = singleHeader(response, "Content-Type", status);
                String encoding = singleHeader(response, "Content-Encoding", status);
                byte[] bytes = new byte[0];
                if (response.getEntity() != null) {
                    if (response.getEntity().getContentLength() > MAX_BYTES) {
                        throw new TransportFailure("RESPONSE_TOO_LARGE", true, status);
                    }
                    bytes = bounded(response.getEntity().getContent(), status);
                    if (encoding != null && !encoding.equalsIgnoreCase("identity")) {
                        if (!encoding.equalsIgnoreCase("gzip")) throw new TransportFailure("INVALID_RESPONSE", true, status);
                        try (InputStream decoded = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                            bytes = bounded(decoded, status);
                        }
                    }
                }
                return new TransportResponse(status, bytes, contentType);
            } finally {
                // Cancel before close to prevent Apache from draining a rejected/unbounded response entity.
                outgoing.cancel();
                try {
                    response.close();
                } catch (IOException ignored) {
                    // Cancellation may close the socket first. Cleanup must not replace the response or its refusal.
                }
            }
        } catch (TransportFailure failure) {
            throw failure;
        } catch (IOException | RuntimeException exception) {
            throw new TransportFailure(status == null ? "IO_FAILURE" : "INVALID_RESPONSE", mayHaveBeenSent.get(), status);
        } finally {
            outgoing.cancel();
            client.close(CloseMode.IMMEDIATE);
        }
    }

    private static void requireHeaders(ClassicHttpResponse response, int status) {
        int bytes = 0;
        Header[] headers = response.getHeaders();
        if (headers.length > MAX_HEADERS) throw new TransportFailure("INVALID_RESPONSE", true, status);
        for (Header header : headers) {
            bytes += header.getName().length() + header.getValue().length() + 4;
            if (bytes > MAX_HEADER_BYTES) throw new TransportFailure("INVALID_RESPONSE", true, status);
        }
    }

    private static String singleHeader(ClassicHttpResponse response, String name, int status) {
        Header[] headers = response.getHeaders(name);
        if (headers.length > 1) throw new TransportFailure("INVALID_RESPONSE", true, status);
        return headers.length == 0 ? null : headers[0].getValue().trim();
    }

    private static byte[] bounded(InputStream input, Integer status) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new TransportFailure("RESPONSE_TOO_LARGE", true, status);
        return bytes;
    }

    private static Timeout timeout(Duration duration) {
        return Timeout.ofMilliseconds(Math.max(1, duration.toMillis()));
    }

    @Override
    @PreDestroy
    public void close() {
        workers.shutdownNow();
    }

    public record TransportRequest(URI target, String method, Map<String, String> headers, byte[] body,
                                   Supplier<InetAddress> address) {
        public TransportRequest {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            body = body == null ? null : body.clone();
        }

        @Override
        public byte[] body() { return body == null ? null : body.clone(); }

        @Override
        public String toString() { return "ConnectorTransportRequest[redacted]"; }
    }

    public record TransportResponse(int status, byte[] body, String contentType) {
        public TransportResponse { body = body == null ? new byte[0] : body.clone(); }

        @Override
        public byte[] body() { return body.clone(); }

        @Override
        public String toString() { return "ConnectorTransportResponse[status=" + status + "]"; }
    }

    public static final class TransportFailure extends RuntimeException {
        private final String code;
        private final boolean mayHaveBeenSent;
        private final Integer httpStatus;

        public TransportFailure(String code, boolean mayHaveBeenSent, Integer httpStatus) {
            super("Connector HTTP request failed.");
            this.code = code;
            this.mayHaveBeenSent = mayHaveBeenSent;
            this.httpStatus = httpStatus;
        }

        public String code() { return code; }
        public boolean mayHaveBeenSent() { return mayHaveBeenSent; }
        public Integer httpStatus() { return httpStatus; }
    }
}
