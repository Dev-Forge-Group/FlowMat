package org.myweb.flowmat.domain.connector.application;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

/** Deployment allowlists are checked again at execution and after DNS resolution. */
@Component
public class ConnectorTargetPolicy {
    private final ConnectorHttpProperties properties;

    public ConnectorTargetPolicy(ConnectorHttpProperties properties) {
        this.properties = properties;
    }

    public String normalizeOrigin(String value) {
        if (value == null || value.isEmpty() || value.length() > 512 || !ascii(value) || value.indexOf('\\') >= 0) {
            throw invalid("baseOrigin must be an HTTP or HTTPS origin without credentials or a path.");
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("https") && !scheme.equals("http")) || uri.getRawAuthority() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (!uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))
                || uri.getRawAuthority().endsWith(":")) {
                throw invalid("baseOrigin must be an HTTP or HTTPS origin without credentials or a path.");
            }
            String host = normalizedHost(uri.getHost());
            int port = uri.getPort();
            if (port == 0 || port < -1 || port > 65535) throw invalid("baseOrigin port is invalid.");
            if (port == (scheme.equals("https") ? 443 : 80)) port = -1;
            return new URI(scheme, null, host, port, null, null, null).toASCIIString();
        } catch (URISyntaxException exception) {
            throw invalid("baseOrigin must be an HTTP or HTTPS origin without credentials or a path.");
        }
    }

    public void validateProfile(String projectId, String baseOrigin, String credentialAlias) {
        String origin = normalizeOrigin(baseOrigin);
        if (properties.getAllowedOrigins() == null || properties.getAllowedOrigins().size() > 256) {
            throw invalid("allowedOrigins configuration is invalid.");
        }
        boolean approved = false;
        for (String registered : properties.getAllowedOrigins()) {
            if (origin.equals(normalizeOrigin(registered))) approved = true;
        }
        if (!approved) throw invalid("baseOrigin is not registered for connector access.");
        if (origin.startsWith("http:") && !properties.isAllowPrivateHttp()) {
            throw invalid("baseOrigin requires HTTPS unless private HTTP is enabled.");
        }
        ConnectorCredentials.registered(properties, projectId, credentialAlias);
    }

    URI target(String projectId, String baseOrigin, String relativePath, String alias) {
        requireEnabled();
        validateProfile(projectId, baseOrigin, alias);
        validatePath(relativePath);
        return URI.create(normalizeOrigin(baseOrigin) + relativePath);
    }

    InetAddress[] validateAddresses(URI target, InetAddress[] answers) {
        requireEnabled();
        if (target == null || target.getScheme() == null
            || (!target.getScheme().equals("https") && !target.getScheme().equals("http"))) {
            throw invalid("baseOrigin scheme is invalid.");
        }
        boolean http = target.getScheme().equals("http");
        if (http && !properties.isAllowPrivateHttp()) throw invalid("baseOrigin requires HTTPS.");
        List<Cidr> privateCidrs = privateCidrs();
        if (answers == null || answers.length == 0 || answers.length > 64) {
            throw invalid("baseOrigin DNS answers are unavailable or exceed the limit.");
        }
        for (InetAddress address : answers) {
            if (address == null) throw invalid("baseOrigin DNS answers are invalid.");
            byte[] bytes = address.getAddress();
            if (hardDenied(bytes)) throw invalid("baseOrigin resolves to a prohibited address.");
            boolean privateAddress = privateAddress(bytes);
            if (privateAddress && privateCidrs.stream().noneMatch(cidr -> cidr.contains(bytes))) {
                throw invalid("baseOrigin private address is not allowed by privateCidrs.");
            }
            if (http && !privateAddress) throw invalid("baseOrigin HTTP access requires an approved private address.");
        }
        requireEnabled();
        return answers.clone();
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) throw invalid("connector HTTP execution is disabled.");
    }

    private static String normalizedHost(String value) {
        if (value == null || value.isEmpty() || value.endsWith(".")) throw invalid("baseOrigin host is invalid.");
        String host = value.toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            String literal = host.substring(1, host.length() - 1);
            if (!literal.matches("[0-9a-f:]+") || literal.indexOf(':') < 0) {
                throw invalid("baseOrigin host must not use an ambiguous IP literal.");
            }
            byte[] bytes = literalAddress(literal);
            if (bytes.length != 16) throw invalid("baseOrigin host must not use an IPv4-mapped literal.");
            try {
                return InetAddress.getByAddress(bytes).getHostAddress();
            } catch (UnknownHostException exception) {
                throw invalid("baseOrigin host is invalid.");
            }
        }
        if (host.matches("[0-9.]+")) {
            ipv4(host);
            return host;
        }
        if (host.length() > 253) throw invalid("baseOrigin host is invalid.");
        for (String label : host.split("\\.", -1)) {
            if (label.startsWith("xn--") || !label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
                throw invalid("baseOrigin host must use unambiguous ASCII DNS labels.");
            }
        }
        return host;
    }

    private static void validatePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 2048 || !ascii(path) || !path.startsWith("/")
            || path.startsWith("//") || path.indexOf('\\') >= 0 || path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
            throw invalid("relativePath must be a static absolute path without query or fragment.");
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c") || lower.contains("%25")) {
            throw invalid("relativePath must not contain encoded path delimiters or dot segments.");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) throw invalid("relativePath must not contain dot segments.");
        }
        try {
            URI uri = new URI(path);
            if (uri.getRawAuthority() != null || uri.getScheme() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getRawPath() == null || !ascii(uri.getPath())) {
                throw invalid("relativePath must be a static absolute path.");
            }
        } catch (URISyntaxException exception) {
            throw invalid("relativePath must be a static absolute path.");
        }
    }

    void validatePrivateCidrsConfiguration() {
        privateCidrs();
    }

    private List<Cidr> privateCidrs() {
        List<String> configured = properties.getPrivateCidrs();
        if (configured == null || configured.size() > 64) throw invalid("privateCidrs configuration is invalid.");
        List<Cidr> cidrs = new ArrayList<>();
        for (String value : configured) {
            try {
                if (value == null || value.length() > 64) throw new IllegalArgumentException();
                String[] parts = value.split("/", -1);
                if (parts.length != 2 || !parts[1].matches("[0-9]{1,3}")) throw new IllegalArgumentException();
                byte[] address = literalAddress(parts[0]);
                int prefix = Integer.parseInt(parts[1]);
                if (prefix > address.length * 8) throw new IllegalArgumentException();
                cidrs.add(new Cidr(address, prefix));
            } catch (IllegalArgumentException | BusinessException exception) {
                throw invalid("privateCidrs configuration is invalid.");
            }
        }
        return cidrs;
    }

    private static byte[] literalAddress(String value) {
        if (value.indexOf(':') < 0) return ipv4(value);
        // Only hexadecimal literal syntax reaches InetAddress: this cannot cause a DNS lookup.
        if (!value.matches("[0-9a-fA-F:]+")) throw invalid("baseOrigin address literal is invalid.");
        try {
            return InetAddress.getByName(value).getAddress();
        } catch (UnknownHostException exception) {
            throw invalid("baseOrigin address literal is invalid.");
        }
    }

    private static byte[] ipv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) throw invalid("baseOrigin address literal is invalid.");
        byte[] bytes = new byte[4];
        for (int index = 0; index < 4; index++) {
            if (!parts[index].matches("0|[1-9][0-9]{0,2}")) throw invalid("baseOrigin address literal is invalid.");
            int number = Integer.parseInt(parts[index]);
            if (number > 255) throw invalid("baseOrigin address literal is invalid.");
            bytes[index] = (byte) number;
        }
        return bytes;
    }

    private static boolean privateAddress(byte[] address) {
        if (address.length == 4) {
            int first = address[0] & 255;
            int second = address[1] & 255;
            return first == 10 || first == 172 && second >= 16 && second <= 31 || first == 192 && second == 168;
        }
        return address.length == 16 && (address[0] & 254) == 252;
    }

    private static boolean hardDenied(byte[] bytes) {
        if (bytes.length == 4) {
            int a = bytes[0] & 255;
            int b = bytes[1] & 255;
            int c = bytes[2] & 255;
            int d = bytes[3] & 255;
            return a == 0 || a == 127 || a == 169 && b == 254 || a >= 224
                || a == 100 && b >= 64 && b <= 127
                || a == 192 && (b == 0 && (c == 0 || c == 2) || b == 88 && c == 99)
                || a == 198 && (b == 18 || b == 19 || b == 51 && c == 100)
                || a == 203 && b == 0 && c == 113
                || a == 168 && b == 63 && c == 129 && d == 16;
        }
        if (bytes.length != 16) return true;
        if (matches(bytes, new int[] {0xfd, 0, 0x0e, 0xc2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0x54})) return true;
        if (matches(bytes, new int[] {0xfd, 0x20, 0, 0xce, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0x54})) return true;
        if (privateAddress(bytes)) return false;
        // Fail closed for non-global families, translation/tunnel and special-purpose ranges.
        if ((bytes[0] & 224) != 32) return true;
        return bytes[0] == 32 && bytes[1] == 1 && ((bytes[2] & 254) == 0 || bytes[2] == 13 && (bytes[3] & 255) == 184)
            || bytes[0] == 32 && bytes[1] == 2
            || (bytes[0] & 255) == 63 && (bytes[1] & 255) == 255 && (bytes[2] & 240) == 0;
    }

    private static boolean matches(byte[] address, int[] expected) {
        for (int index = 0; index < expected.length; index++) {
            if ((address[index] & 255) != expected[index]) return false;
        }
        return true;
    }

    private static boolean ascii(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 32 || character > 126 || character == 127) return false;
        }
        return true;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    private record Cidr(byte[] network, int prefix) {
        boolean contains(byte[] address) {
            if (address.length != network.length) return false;
            for (int index = 0; index < prefix / 8; index++) {
                if (address[index] != network[index]) return false;
            }
            int remaining = prefix % 8;
            return remaining == 0 || ((address[prefix / 8] ^ network[prefix / 8]) & (255 << (8 - remaining))) == 0;
        }
    }
}
