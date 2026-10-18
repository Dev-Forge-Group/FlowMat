package org.myweb.flowmat.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowAccess;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.security.AuthUser;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class StompAuthChannelInterceptorTest {

    @ParameterizedTest
    @ValueSource(strings = {"/topic/**", "/topic/workflow/*/graph", "/topic/workflow/workflow-1/**",
        "/topic/other", "/topic/workflow/workflow-1/unknown"})
    void rejectsWildcardAndUnknownSubscriptions(String destination) {
        assertRejected(StompCommand.SUBSCRIBE, destination);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/workflow/workflow-1/graph", "/topic/workflow/workflow-1/presence",
        "/app/workflow/*/presence", "/app/unknown"})
    void rejectsBrokerSendsAndUnknownApplicationDestinations(String destination) {
        assertRejected(StompCommand.SEND, destination);
    }

    private void assertRejected(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setLeaveMutable(true);
        accessor.setDestination(destination);
        accessor.setUser(new UsernamePasswordAuthenticationToken(new AuthUser("user-1"), null));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        assertThatThrownBy(() -> new StompAuthChannelInterceptor(jwtProvider, workflowAccess).preSend(message, null))
            .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Mock
    private WorkflowAccess workflowAccess;

    private final JwtProvider jwtProvider = new JwtProvider(
        "flowmat-local-dev-secret-key-32bytes!!",
        3600,
        2592000
    );

    @Test
    void connectWithAccessTokenSetsAuthenticatedPrincipal() {
        StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor(jwtProvider, workflowAccess);
        String accessToken = jwtProvider.generateAccessToken("user-1");
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        accessor.setNativeHeader("Authorization", "Bearer " + accessToken);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> intercepted = interceptor.preSend(message, null);
        StompHeaderAccessor result = StompHeaderAccessor.wrap(intercepted);

        assertThat(result.getUser()).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        UsernamePasswordAuthenticationToken authentication =
            (UsernamePasswordAuthenticationToken) result.getUser();
        assertThat(authentication.getPrincipal()).isInstanceOf(AuthUser.class);
        assertThat(((AuthUser) authentication.getPrincipal()).getUserId()).isEqualTo("user-1");
    }

    @Test
    void connectRejectsRefreshToken() {
        StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor(jwtProvider, workflowAccess);
        String refreshToken = jwtProvider.generateRefreshToken("user-1");
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        accessor.setNativeHeader("Authorization", "Bearer " + refreshToken);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.TOKEN_TYPE_INVALID);
    }

    @Test
    void connectRejectsExpiredAccessToken() {
        StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor(jwtProvider, workflowAccess);
        String expiredToken = jwtProvider.generateAccessToken("user-1", "user", -1);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        accessor.setNativeHeader("Authorization", "Bearer " + expiredToken);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void connectRejectsMalformedAccessToken() {
        StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor(jwtProvider, workflowAccess);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        accessor.setNativeHeader("Authorization", "Bearer not-a-jwt");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    void workflowSubscribeRequiresAuthenticatedUser() {
        StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor(jwtProvider, workflowAccess);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setLeaveMutable(true);
        accessor.setDestination("/topic/workflow/workflow-1/presence");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    void workflowSendChecksWorkflowMembership() {
        StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor(jwtProvider, workflowAccess);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setLeaveMutable(true);
        accessor.setDestination("/app/workflow/workflow-7/presence");
        accessor.setUser(new UsernamePasswordAuthenticationToken(new AuthUser("user-7"), null));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        interceptor.preSend(message, null);

        verify(workflowAccess).requireWrite("workflow-7", "user-7");
    }

    @Test
    void workflowSubscribeChecksWorkflowReadMembership() {
        StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor(jwtProvider, workflowAccess);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setLeaveMutable(true);
        accessor.setDestination("/topic/workflow/workflow-8/graph");
        accessor.setUser(new UsernamePasswordAuthenticationToken(new AuthUser("user-8"), null));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        interceptor.preSend(message, null);

        verify(workflowAccess).requireRead("workflow-8", "user-8");
    }
}
