package org.myweb.flowmat.global.security.oauth;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

public class CustomAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private static final String AUTHORIZATION_BASE_URI = "/oauth2/authorization";
    private static final String LINK_STATE_PREFIX = "link.";
    private final OAuth2AuthorizationRequestResolver delegate;

    public CustomAuthorizationRequestResolver(ClientRegistrationRepository repository) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(repository, AUTHORIZATION_BASE_URI);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return customize(delegate.resolve(request), request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return customize(delegate.resolve(request, clientRegistrationId), request);
    }

    private OAuth2AuthorizationRequest customize(OAuth2AuthorizationRequest request, HttpServletRequest httpRequest) {
        if (request == null) {
            return null;
        }

        String registrationId = (String) request.getAttribute(OAuth2ParameterNames.REGISTRATION_ID);
        Map<String, Object> additional = new LinkedHashMap<>(request.getAdditionalParameters());
        if ("google".equals(registrationId)) {
            additional.put("prompt", "select_account");
        }

        OAuth2AuthorizationRequest.Builder builder = OAuth2AuthorizationRequest.from(request)
            .additionalParameters(additional);

        String mode = httpRequest.getParameter("mode");
        String linkToken = httpRequest.getParameter("linkToken");
        String returnTo = httpRequest.getParameter("returnTo");
        if ("link".equalsIgnoreCase(mode) && hasText(linkToken) && hasText(returnTo)) {
            String payload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((linkToken + "\n" + returnTo).getBytes(StandardCharsets.UTF_8));
            String originalState = request.getState() == null ? "" : request.getState();
            builder.state(LINK_STATE_PREFIX + payload + "." + originalState);
        }

        return builder.build();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
