package org.myweb.flowmat.domain.connector.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

class ConnectorTargetPolicyTest {
    private final ConnectorHttpProperties properties = new ConnectorHttpProperties();
    private final ConnectorTargetPolicy policy = new ConnectorTargetPolicy(properties);

    @Test
    void normalizesOnlyOriginCaseDefaultPortsAndTheOptionalRootSlash() {
        assertEquals("https://api.example.com", policy.normalizeOrigin("HTTPS://API.Example.COM:443/"));
        assertEquals("http://api.example.com", policy.normalizeOrigin("http://api.example.com:80"));
        assertEquals("https://api.example.com:8443", policy.normalizeOrigin("https://api.example.com:8443/"));
        assertEquals("https://8.8.8.8", policy.normalizeOrigin("https://8.8.8.8"));
        assertEquals(policy.normalizeOrigin("https://[2001:4860:4860::8888]"),
            policy.normalizeOrigin("https://[2001:4860:4860:0:0:0:0:8888]:443/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " https://api.example.com", "https://api.example.com ",
        "https://user:secret@api.example.com", "https://api.example.com/path", "https://api.example.com//",
        "https://api.example.com?token=secret", "https://api.example.com#secret", "ftp://api.example.com",
        "https://api.example.com.", "https://api.example.com:", "https://api.example.com:0",
        "https://api.example.com:65536", "https://api.example.com\\@evil.example", "https://api.example.com\n",
        "https://\u0430pi.example.com", "https://xn--80ak6aa92e.com", "https://127.1", "https://2130706433",
        "https://0177.0.0.1", "https://[fe80::1%25eth0]", "https://[::ffff:127.0.0.1]"})
    void rejectsAmbiguousOriginsWithoutEchoingTheirValues(String origin) {
        rejected(() -> policy.normalizeOrigin(origin), origin);
    }

    @Test
    void profileValidationUsesExactCanonicalAllowlistAndProjectBoundAliasWithoutDnsOrSecrets() {
        properties.setAllowedOrigins(List.of("HTTPS://Unresolvable.Invalid:443/"));
        ConnectorHttpProperties.Credential credential = new ConnectorHttpProperties.Credential();
        credential.setProjectId("project-one");
        credential.setKind(ConnectorHttpProperties.Kind.BEARER);
        credential.setTokenEnvironment("NOT_PRESENT_IN_TEST_ENV");
        properties.setCredentials(Map.of("registered", credential));

        policy.validateProfile("project-one", "https://unresolvable.invalid", "registered");
        policy.validateProfile("project-one", "https://unresolvable.invalid", null);
        policy.validateProfile("project-one", "https://unresolvable.invalid", "");
        rejected(() -> policy.validateProfile("project-two", "https://unresolvable.invalid", "registered"), null);
        rejected(() -> policy.validateProfile("project-one", "https://other.invalid", null), null);
        rejected(() -> policy.validateProfile("project-one", "https://unresolvable.invalid", "unknown"), null);
        assertFalse(properties.isEnabled());
    }

    @Test
    void executionRevalidatesDisabledFlagAndRegistration() {
        properties.setAllowedOrigins(List.of("https://api.example.com"));
        rejected(() -> policy.target("p", "https://api.example.com", "/v1/setup", null), null);
        properties.setEnabled(true);
        assertEquals(URI.create("https://api.example.com/v1/setup"),
            policy.target("p", "HTTPS://API.EXAMPLE.COM:443/", "/v1/setup", null));
        properties.setAllowedOrigins(List.of());
        rejected(() -> policy.target("p", "https://api.example.com", "/v1/setup", null), null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "v1/setup", "//evil.example/setup", "/../setup", "/a/./setup", "/a/..",
        "/a?key=secret", "/a#secret", "/a\\b", "/a\r\nb", "/%2e%2e/setup", "/%2F%2Fevil",
        "/a%5cb", "/%252e%252e/setup", "/${secret}", "/{site}", "/a%", "/a%0aheader"})
    void rejectsNonStaticOrAmbiguousRelativePaths(String path) {
        enable("https://api.example.com");
        rejected(() -> policy.target("p", "https://api.example.com", path, null), null);
    }

    @Test
    void targetPreservesValidEncodedPathWithoutAddingQueryOrResolvingTemplates() {
        enable("https://api.example.com");
        assertEquals("https://api.example.com/v1/site%20name/setup.json",
            policy.target("p", "https://api.example.com", "/v1/site%20name/setup.json", null).toASCIIString());
    }

    @Test
    void publicHttpsAddressesAreReturnedDefensivelyOnlyAfterEveryAnswerIsApproved() throws Exception {
        enable("https://api.example.com");
        InetAddress[] answers = {ip("8.8.8.8"), ip("2606:4700:4700::1111")};
        InetAddress[] approved = policy.validateAddresses(URI.create("https://api.example.com/setup"), answers);
        assertArrayEquals(answers, approved);
        assertNotSame(answers, approved);
        rejected(() -> policy.validateAddresses(URI.create("https://api.example.com/setup"),
            new InetAddress[] {answers[0], ipUnchecked("127.0.0.1")}), null);
        rejected(() -> policy.validateAddresses(URI.create("https://api.example.com/setup"), new InetAddress[0]), null);
        rejected(() -> policy.validateAddresses(URI.create("https://api.example.com/setup"), null), null);
        rejected(() -> policy.validateAddresses(URI.create("https://api.example.com/setup"), new InetAddress[] {null}), null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "0.1.2.3", "100.64.0.1", "127.0.0.1", "169.254.169.254",
        "169.254.170.2", "192.0.0.8", "192.0.2.1", "192.88.99.1", "198.18.0.1", "198.51.100.1",
        "203.0.113.1", "224.0.0.1", "240.1.2.3", "255.255.255.255", "::", "::1", "fe80::1", "ff02::1",
        "fd00:ec2::254", "fd20:ce::254", "2001:db8::1", "2001::1", "2002:808:808::1", "3fff::1"})
    void hardDeniedNetworksCannotBeEnabledWithAnAdministratorCidr(String literal) throws Exception {
        enable("https://api.example.com");
        properties.setPrivateCidrs(List.of("0.0.0.0/0", "::/0"));
        rejected(() -> policy.validateAddresses(URI.create("https://api.example.com/setup"),
            new InetAddress[] {ipUnchecked(literal)}), null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.2.3.4", "172.16.0.1", "192.168.4.1", "fd12:3456::1"})
    void privateAddressesNeedAnExplicitMatchingCidr(String literal) throws Exception {
        enable("https://api.example.com");
        InetAddress[] answers = {ip(literal)};
        rejected(() -> policy.validateAddresses(URI.create("https://api.example.com/setup"), answers), null);
        properties.setPrivateCidrs(List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fd12:3456::/32"));
        assertArrayEquals(answers, policy.validateAddresses(URI.create("https://api.example.com/setup"), answers));
        properties.setPrivateCidrs(List.of("192.168.50.0/24"));
        rejected(() -> policy.validateAddresses(URI.create("https://api.example.com/setup"), answers), null);
    }

    @Test
    void plaintextHttpNeedsBothTheDeploymentSwitchAndOnlyApprovedPrivateAnswers() throws Exception {
        enable("http://equipment.example.com:8080");
        rejected(() -> policy.validateProfile("p", "http://equipment.example.com:8080", null), null);
        properties.setAllowPrivateHttp(true);
        policy.validateProfile("p", "http://equipment.example.com:8080", null);
        URI target = policy.target("p", "http://equipment.example.com:8080", "/setup", null);
        properties.setPrivateCidrs(List.of("192.168.50.0/24"));
        InetAddress[] privateAnswer = {ip("192.168.50.6")};
        assertArrayEquals(privateAnswer, policy.validateAddresses(target, privateAnswer));
        rejected(() -> policy.validateAddresses(target, new InetAddress[] {ipUnchecked("8.8.8.8")}), null);
        properties.setAllowPrivateHttp(false);
        rejected(() -> policy.validateAddresses(target, privateAnswer), null);
    }

    @Test
    void invalidCidrConfigurationAndDisablingDuringDnsFailClosed() throws Exception {
        enable("https://api.example.com");
        properties.setPrivateCidrs(List.of("localhost/8"));
        InetAddress[] answers = {ip("8.8.8.8")};
        rejected(() -> policy.validateAddresses(URI.create("https://api.example.com/setup"), answers), null);
        properties.setPrivateCidrs(List.of());
        properties.setEnabled(false);
        rejected(() -> policy.validateAddresses(URI.create("https://api.example.com/setup"), answers), null);
    }

    private void enable(String origin) {
        properties.setEnabled(true);
        properties.setAllowedOrigins(List.of(origin));
    }

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    private static InetAddress ipUnchecked(String literal) {
        try { return ip(literal); } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static void rejected(Runnable operation, String value) {
        BusinessException exception = assertThrows(BusinessException.class, operation::run);
        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertTrue(exception.getMessage().length() < 160);
        if (value != null && !value.isEmpty()) assertFalse(exception.getMessage().contains(value));
    }
}
