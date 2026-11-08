package org.myweb.flowmat.global.security.oauth;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import lombok.Getter;
import org.myweb.flowmat.domain.user.domain.entity.User;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

@Getter
public class UserContext implements OAuth2User {

    private final User user;
    private final Map<String, Object> attributes;
    private final boolean signupRequired;
    private final String provider;
    private final String providerId;
    private final String email;
    private final String nickname;

    private UserContext(
        User user,
        Map<String, Object> attributes,
        boolean signupRequired,
        String provider,
        String providerId,
        String email,
        String nickname
    ) {
        this.user = user;
        this.attributes = attributes;
        this.signupRequired = signupRequired;
        this.provider = provider;
        this.providerId = providerId;
        this.email = email;
        this.nickname = nickname;
    }

    public static UserContext registered(User user, Map<String, Object> attributes, String provider,
                                         String providerId, String email, String nickname) {
        return new UserContext(user, attributes, false, provider, providerId, email, nickname);
    }

    public static UserContext signupPending(String provider, String providerId, String email,
                                            String nickname, Map<String, Object> attributes) {
        return new UserContext(null, attributes, true, provider, providerId, email, nickname);
    }

    public String getUserId() {
        return user == null ? null : user.getUserId();
    }

    public String getRole() {
        return user == null || user.getUserRole() == null ? "user" : user.getUserRole();
    }

    @Override
    public Collection<SimpleGrantedAuthority> getAuthorities() {
        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + getRole().toUpperCase()));
    }

    @Override
    public String getName() {
        if (user != null) {
            return user.getUserId();
        }
        return (provider == null ? "oauth" : provider) + ":" + (providerId == null ? "unknown" : providerId);
    }
}
