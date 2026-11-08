package org.myweb.flowmat.global.security.oauth;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.user.domain.entity.SocialAccount;
import org.myweb.flowmat.domain.user.domain.entity.User;
import org.myweb.flowmat.domain.user.repository.SocialAccountRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final SocialAccountRepository socialAccountRepository;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(request);
        String provider = request.getClientRegistration().getRegistrationId();
        Map<String, Object> attributes = oAuth2User.getAttributes();
        ParsedOAuth parsed = parse(provider, attributes);

        SocialAccount linked = socialAccountRepository
            .findByProviderAndProviderUserId(parsed.provider().toUpperCase(), parsed.providerId())
            .orElse(null);
        if (linked != null) {
            User user = requireLoginable(linked.getUser());
            return UserContext.registered(
                user,
                attributes,
                parsed.provider().toUpperCase(),
                parsed.providerId(),
                parsed.email(),
                parsed.nickname()
            );
        }

        return UserContext.signupPending(
            parsed.provider().toUpperCase(),
            parsed.providerId(),
            parsed.email(),
            parsed.nickname(),
            attributes
        );
    }

    @SuppressWarnings("unchecked")
    private ParsedOAuth parse(String provider, Map<String, Object> attributes) {
        if ("google".equals(provider)) {
            return new ParsedOAuth(
                provider,
                asText(attributes.get("sub")),
                asText(attributes.get("email")),
                asText(attributes.get("name"))
            );
        }

        if ("kakao".equals(provider)) {
            String providerId = asText(attributes.get("id"));
            Map<String, Object> account = (Map<String, Object>) attributes.get("kakao_account");
            Map<String, Object> profile = account == null ? null : (Map<String, Object>) account.get("profile");
            return new ParsedOAuth(
                provider,
                providerId,
                account == null ? null : asText(account.get("email")),
                profile == null ? null : asText(profile.get("nickname"))
            );
        }

        throw new OAuth2AuthenticationException(new OAuth2Error("unsupported_provider"), provider);
    }

    private User requireLoginable(User user) {
        if (user == null || !canLogin(user)) {
            throw new OAuth2AuthenticationException(new OAuth2Error("access_denied"), "blocked_user");
        }
        return user;
    }

    private boolean canLogin(User user) {
        if (user == null) {
            return false;
        }
        String status = user.getUserStatus();
        return !"Y".equalsIgnoreCase(user.getDeleteYn())
            && (status == null || (!"withdrawn".equalsIgnoreCase(status) && !"locked".equalsIgnoreCase(status) && !"dormant".equalsIgnoreCase(status)));
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record ParsedOAuth(String provider, String providerId, String email, String nickname) {
    }
}
