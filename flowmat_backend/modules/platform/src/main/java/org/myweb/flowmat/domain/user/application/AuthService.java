package org.myweb.flowmat.domain.user.application;

import org.myweb.flowmat.domain.user.api.dto.request.FindEmailRequest;
import org.myweb.flowmat.domain.user.api.dto.request.DormantReactivationRequest;
import org.myweb.flowmat.domain.user.api.dto.request.DormantTokenRequest;
import org.myweb.flowmat.domain.user.api.dto.request.OAuthSignupCompleteRequest;
import org.myweb.flowmat.domain.user.api.dto.request.PasswordResetConfirmRequest;
import org.myweb.flowmat.domain.user.api.dto.request.PasswordResetRequest;
import org.myweb.flowmat.domain.user.api.dto.request.RefreshTokenRequest;
import org.myweb.flowmat.domain.user.api.dto.request.SocialLinkStartRequest;
import org.myweb.flowmat.domain.user.api.dto.request.UserLoginRequest;
import org.myweb.flowmat.domain.user.api.dto.request.UserSignupRequest;
import org.myweb.flowmat.domain.user.api.dto.response.SocialLinkStartResponse;
import org.myweb.flowmat.domain.user.api.dto.response.UserTokenResponse;

public interface AuthService {

    void sendEmailCode(String userEmail);

    void verifyEmailCode(String userEmail, String code);

    void signup(UserSignupRequest request);

    boolean checkNicknameAvailable(String nickname);

    UserTokenResponse login(UserLoginRequest request, String userAgent, String ipAddress);

    UserTokenResponse issueGuestToken(String guestSid, String currentAccessToken);

    UserTokenResponse refresh(RefreshTokenRequest request, String fallbackRefreshToken, String userAgent, String ipAddress);

    void logout(String refreshToken);

    void logoutAll(String userId);

    String findEmail(FindEmailRequest request);

    void requestPasswordReset(PasswordResetRequest request);

    void verifyPasswordResetToken(String token);

    void confirmPasswordReset(PasswordResetConfirmRequest request);

    void requestDormantReactivation(DormantReactivationRequest request);

    void reactivateDormant(DormantTokenRequest request);

    UserTokenResponse completeKakaoSignup(OAuthSignupCompleteRequest request, String userAgent, String ipAddress);

    UserTokenResponse completeGoogleSignup(OAuthSignupCompleteRequest request, String userAgent, String ipAddress);

    SocialLinkStartResponse startSocialLink(String userId, SocialLinkStartRequest request);
}
