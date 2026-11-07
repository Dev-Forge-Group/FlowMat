package org.myweb.flowmat.global.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fails production startup with one message listing every misconfigured setting.
 *
 * <p>Several beans read these values through {@code @Value} with localhost fallbacks that are convenient in
 * development but silently wrong in production (e.g. password-reset mails linking to localhost).
 */
@Component
@Profile("prod")
public class ProductionConfigValidator implements InitializingBean {

    /** Development placeholders that must never sign production tokens. */
    private static final Set<String> KNOWN_WEAK_SECRETS = Set.of(
        "flowmat-local-dev-secret-key-32bytes!!",
        "smoke-test-secret-key-that-is-at-least-32-bytes",
        "change-me",
        "changeme"
    );

    private final String frontendUrl;
    private final String oauth2RedirectUri;
    private final String corsAllowedOrigins;
    private final String jwtSecret;
    private final String mailHost;
    private final String dbUrl;
    private final String dbUsername;
    private final String dbPassword;
    private final String redisHost;
    private final String redisPort;
    private final String mailPort;
    private final String mailUsername;
    private final String mailPassword;
    private final boolean validateInfrastructure;

    public ProductionConfigValidator(
        String frontendUrl,
        String oauth2RedirectUri,
        String corsAllowedOrigins,
        String jwtSecret,
        String mailHost
    ) {
        this(frontendUrl, oauth2RedirectUri, corsAllowedOrigins, jwtSecret, mailHost,
            "", "", "", "", "", "", "", "", false);
    }

    @Autowired
    public ProductionConfigValidator(
        @Value("${app.frontend-url:}") String frontendUrl,
        @Value("${app.oauth2.redirect-uri:}") String oauth2RedirectUri,
        @Value("${app.cors.allowed-origins:}") String corsAllowedOrigins,
        @Value("${jwt.secret:}") String jwtSecret,
        @Value("${spring.mail.host:}") String mailHost,
        @Value("${spring.datasource.url:}") String dbUrl,
        @Value("${spring.datasource.username:}") String dbUsername,
        @Value("${spring.datasource.password:}") String dbPassword,
        @Value("${spring.data.redis.host:}") String redisHost,
        @Value("${spring.data.redis.port:}") String redisPort,
        @Value("${spring.mail.port:}") String mailPort,
        @Value("${spring.mail.username:}") String mailUsername,
        @Value("${spring.mail.password:}") String mailPassword
    ) {
        this(frontendUrl, oauth2RedirectUri, corsAllowedOrigins, jwtSecret, mailHost,
            dbUrl, dbUsername, dbPassword, redisHost, redisPort, mailPort, mailUsername, mailPassword, true);
    }

    private ProductionConfigValidator(
        String frontendUrl,
        String oauth2RedirectUri,
        String corsAllowedOrigins,
        String jwtSecret,
        String mailHost,
        String dbUrl,
        String dbUsername,
        String dbPassword,
        String redisHost,
        String redisPort,
        String mailPort,
        String mailUsername,
        String mailPassword,
        boolean validateInfrastructure
    ) {
        this.frontendUrl = frontendUrl;
        this.oauth2RedirectUri = oauth2RedirectUri;
        this.corsAllowedOrigins = corsAllowedOrigins;
        this.jwtSecret = jwtSecret;
        this.mailHost = mailHost;
        this.dbUrl = dbUrl;
        this.dbUsername = dbUsername;
        this.dbPassword = dbPassword;
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.mailPort = mailPort;
        this.mailUsername = mailUsername;
        this.mailPassword = mailPassword;
        this.validateInfrastructure = validateInfrastructure;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = validate();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid production configuration:\n - " + String.join("\n - ", problems));
        }
    }

    List<String> validate() {
        List<String> problems = new ArrayList<>();

        requireAbsoluteUrl("app.frontend-url (FRONTEND_URL)", frontendUrl, true, problems);
        requireAbsoluteUrl("app.oauth2.redirect-uri (APP_OAUTH2_REDIRECT_URI)", oauth2RedirectUri, false, problems);

        List<String> origins = Arrays.stream(blankToEmpty(corsAllowedOrigins).split(","))
            .map(String::trim)
            .filter(origin -> !origin.isEmpty())
            .toList();
        if (origins.isEmpty()) {
            problems.add("app.cors.allowed-origins (APP_CORS_ALLOWED_ORIGINS) is empty.");
        }
        for (String origin : origins) {
            if (origin.contains("*")) {
                problems.add("app.cors.allowed-origins must list explicit origins, not '" + origin + "'.");
            } else {
                requireAbsoluteUrl("app.cors.allowed-origins entry", origin, true, problems);
            }
        }

        if (KNOWN_WEAK_SECRETS.contains(blankToEmpty(jwtSecret))) {
            problems.add("jwt.secret (JWT_SECRET) is a development placeholder; generate a random production secret.");
        }
        if (blankToEmpty(jwtSecret).length() < 32) {
            problems.add("jwt.secret (JWT_SECRET) must be at least 32 characters.");
        }
        if (blankToEmpty(mailHost).isEmpty()) {
            problems.add("spring.mail.host (MAIL_HOST) is empty; invite and password-reset mails cannot be sent.");
        }
        if (validateInfrastructure) {
            requireNonBlank("spring.datasource.url (DB_URL)", dbUrl, problems);
            requireNonBlank("spring.datasource.username (DB_USERNAME)", dbUsername, problems);
            requireNonBlank("spring.datasource.password (DB_PASSWORD)", dbPassword, problems);
            requireNonBlank("spring.data.redis.host (REDIS_HOST)", redisHost, problems);
            requirePort("spring.data.redis.port (REDIS_PORT)", redisPort, problems);
            requirePort("spring.mail.port (MAIL_PORT)", mailPort, problems);
            requireNonBlank("spring.mail.username (MAIL_USERNAME)", mailUsername, problems);
            requireNonBlank("spring.mail.password (MAIL_PASSWORD)", mailPassword, problems);
            if (blankToEmpty(dbUrl).isEmpty() || !dbUrl.startsWith("jdbc:")) {
                problems.add("spring.datasource.url (DB_URL) must be a JDBC URL.");
            }
        }
        return problems;
    }

    private static void requireNonBlank(String name, String value, List<String> problems) {
        if (blankToEmpty(value).isEmpty()) {
            problems.add(name + " is empty.");
        }
    }

    private static void requirePort(String name, String value, List<String> problems) {
        String trimmed = blankToEmpty(value);
        if (trimmed.isEmpty()) {
            problems.add(name + " is empty.");
            return;
        }
        try {
            int port = Integer.parseInt(trimmed);
            if (port < 1 || port > 65535) {
                problems.add(name + " must be between 1 and 65535.");
            }
        } catch (NumberFormatException e) {
            problems.add(name + " must be a valid port.");
        }
    }

    private static void requireAbsoluteUrl(String name, String value, boolean required, List<String> problems) {
        String trimmed = blankToEmpty(value);
        if (trimmed.isEmpty()) {
            if (required) {
                problems.add(name + " is empty.");
            }
            return;
        }
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
                problems.add(name + " must be an absolute http(s) URL, got '" + trimmed + "'.");
            } else if (!"https".equalsIgnoreCase(scheme)) {
                problems.add(name + " must use HTTPS in production.");
            }
        } catch (IllegalArgumentException e) {
            problems.add(name + " is not a valid URL: '" + trimmed + "'.");
        }
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
