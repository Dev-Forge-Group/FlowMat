package org.myweb.flowmat.global.security.oauth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OAuthExchangeStore {

    private static final String PREFIX = "auth:oauth:exchange:";

    private final StringRedisTemplate redisTemplate;

    public String issueCode(OAuthExchangeEntry entry, Duration ttl) {
        try {
            String code = UUID.randomUUID().toString().replace("-", "");
            redisTemplate.opsForValue().set(key(code), serialize(entry), ttl);
            return code;
        } catch (DataAccessException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    public OAuthExchangeEntry consume(String code) {
        try {
            String payload = redisTemplate.opsForValue().getAndDelete(key(code));
            if (payload == null || payload.isBlank()) {
                return null;
            }
            return deserialize(payload);
        } catch (DataAccessException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private String key(String code) {
        return PREFIX + code;
    }

    private String serialize(OAuthExchangeEntry entry) {
        return String.join(
            "\n",
            encode(entry.resultType()),
            encode(entry.accessToken()),
            encode(entry.signupToken()),
            encode(entry.provider()),
            encode(entry.deviceId()),
            encode(Boolean.toString(entry.additionalInfoRequired()))
        );
    }

    private OAuthExchangeEntry deserialize(String payload) {
        String[] parts = payload.split("\n", -1);
        if (parts.length != 6) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "OAuth exchange payload is invalid.");
        }
        return new OAuthExchangeEntry(
            decode(parts[0]),
            decode(parts[1]),
            decode(parts[2]),
            decode(parts[3]),
            decode(parts[4]),
            Boolean.parseBoolean(nullToEmpty(decode(parts[5])))
        );
    }

    private String encode(String value) {
        String normalized = value == null ? "" : value;
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(normalized.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        return decoded.isEmpty() ? null : decoded;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record OAuthExchangeEntry(
        String resultType,
        String accessToken,
        String signupToken,
        String provider,
        String deviceId,
        boolean additionalInfoRequired
    ) {
        public static final String LOGIN = "login";
        public static final String SIGNUP_REQUIRED = "signup_required";
    }
}
