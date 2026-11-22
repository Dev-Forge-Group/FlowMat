# 로그인 보안 점검

> **현행 문서** · 확인 2026-11-22(`main` V140). 인증 코드나 설정을 바꾸면 같은 변경에서 고친다.

[로드맵](../status/ROADMAP-2026-11-23.md) §8 다음 달 2번(파일럿 배포)의 "로그인 보안 점검"이다. 파일럿 공장에 주소를 열기 전에 로그인, 토큰, 쿠키가 어떻게 지켜지는지 코드로 확인했다. 점검하면서 빈틈 두 곳을 고쳤다(§2). 남은 것은 §3, 배포할 때 사람이 확인할 것은 §4에 있다.

## 1. 있는 것

| 무엇 | 어떻게 | 어디 |
|---|---|---|
| 비밀번호 저장 | BCrypt(강도 10). 평문은 로그에 남지 않는다 | `SecurityConfig.passwordEncoder`, `AuthDtoLoggingRegressionTest` |
| 로그인 속도 제한 | IP마다 10분에 12번, 계정마다 10분에 8번. 넘으면 429. 값은 `AUTH_LOGIN_IP_LIMIT`·`AUTH_LOGIN_ACCOUNT_LIMIT`로 바꾼다 | `AuthController.login`, `app.auth.login-rate-limit` |
| 계정 잠금 | 5분 안에 5번 틀리면 5분 동안 잠근다(Redis) | `AuthServiceImpl.login` |
| 다른 인증 요청의 제한 | 이메일 코드, 가입, 이메일 찾기, 비밀번호 재설정, 휴면 해제, 게스트 토큰마다 IP·대상별 제한 | `AuthController` |
| 클라이언트 IP | Tomcat이 신뢰하는 내부 프록시의 `X-Forwarded-For`만 쓴다. 앱이 헤더를 직접 읽지 않으므로, 헤더를 위조해도 새 한도가 생기지 않는다 | `server.forward-headers-strategy: native`, `AuthAbuseProtectionIntegrationTest` |
| 접근 토큰 | HS256, 1시간(`JWT_ACCESS_EXPIRY`). `JWT_SECRET`이 32바이트보다 짧으면 앱이 뜨지 않는다 | `JwtProvider` |
| 갱신 토큰 | 30일(`JWT_REFRESH_EXPIRY`), Redis에 기기와 함께 둔다. 한 번 쓰면 버리고 새로 준다. 기기가 다르면 거절한다. 로그아웃하면 그 토큰을, 전체 로그아웃·비밀번호 재설정·휴면 해제를 하면 그 사람의 모든 토큰을 지운다 | `AuthServiceImpl.refresh`, `AuthRedisStore` |
| 갱신 토큰 쿠키 | `flowmat_rt`: HttpOnly, SameSite=Lax, Path=/api/auth. HTTPS 요청이면 Secure | `RefreshTokenCookieService` |
| CSRF | 쿠키로 인증하는 두 요청(`/auth/refresh`, `/auth/logout`)에 double-submit 토큰을 요구한다. 나머지는 Authorization 헤더로만 인증하므로 CSRF 대상이 아니다 | `SecurityConfig` |
| 세션·CORS | 서버 세션 없음(STATELESS). CORS는 `APP_CORS_ALLOWED_ORIGINS`에 적은 주소만 받는다 | `SecurityConfig` |
| 얼굴 로그인 | 기본으로 꺼져 있다(ADR-030) | `APP_AUTH_FACE_LOGIN_ENABLED` |
| 데모 계정 | `demo-owner`는 `dev`·`test` 프로필에서만 만들어진다. 다른 환경의 DB에서는 V18이 V2의 시드를 지운다 | `DemoDataInitializer`, `V18__remove_demo_data_from_non_demo_environment.sql` |

## 2. 이번에 고친 것

- **비밀번호 규칙이 없었다.** 한 글자짜리 비밀번호로도 가입하거나 바꿀 수 있었다. 이제 새 비밀번호는 다음을 지켜야 하고, 어기면 400으로 거절한다.
  - 8자 이상
  - 72바이트 이하: BCrypt는 72바이트까지만 읽으므로, 더 긴 비밀번호는 사용자도 모르게 잘린다.
  - 자기 ID나 이메일과 같지 않을 것
  - 적용하는 곳은 가입, 소셜 가입, 비밀번호 재설정, 프로필에서 바꾸기다. 로그인할 때는 검사하지 않으므로 지금 쓰는 비밀번호는 그대로 된다.
  - 재설정이 거절되면 세션과 재설정 링크는 그대로 남는다.
  - 코드: `PasswordPolicy`. 테스트: `PasswordPolicyTest`, `AuthAbuseProtectionIntegrationTest`.
- **없는 계정의 로그인은 응답이 빨랐다.** 있는 계정은 BCrypt 비교를 하는데 없는 계정은 바로 401을 돌려줬다. 그래서 응답 시간만 재도 어떤 계정이 있는지 알 수 있었다. 이제 없는 계정도 같은 인코더로 한 번 비교한 뒤 같은 401을 돌려준다(`AuthServiceImplTest`).

## 3. 남은 것

파일럿 전에 꼭 막아야 할 것은 아니다. 다만 할지 말지는 사람이 정한다.

| 무엇 | 영향 | 제안 |
|---|---|---|
| 프로필에서 비밀번호를 바꿔도 다른 기기의 갱신 토큰이 남는다(재설정은 모두 끊는다) | 비밀번호를 도둑맞아 바꾼 사람의 다른 기기 세션이 최대 30일 남는다. `POST /auth/logout-all`로 끊을 수는 있지만 화면에는 아직 그 버튼이 없다 | 바꿀 때 다른 기기 토큰을 지우고, 지금 기기에는 새 토큰을 준다. 이렇게 하면 화면 흐름이 바뀌니 정하고 나서 한다 |
| 갱신 토큰을 다시 쓰는지 감지하지 않는다 | 도둑맞은 갱신 토큰이 먼저 쓰이면 정당한 사용자만 끊기고, 훔친 쪽은 계속 갱신한다 | 이미 쓴 토큰이 다시 오면 그 사람의 토큰을 모두 지운다(쓴 jti를 만료 때까지 기억해 둔다) |
| DB의 실패 횟수와 잠금 시각이 남지 않는다 | 로그인 실패는 예외로 끝나 트랜잭션이 롤백되므로 `failed_login_count`·`locked_at`이 저장되지 않는다. 잠금 자체는 Redis에서 동작한다 | 관리 화면에서 이 두 칸을 믿지 않는다. 기록이 필요하면 별도 트랜잭션으로 남긴다 |
| 잠금 응답(403 "locked")을 보면 계정이 있다는 것을 안다 | 속도 제한 안에서만 해 볼 수 있다(계정마다 10분에 8번) | 잠긴 계정에도 401과 같은 문구를 주는 대신, 잠김은 메일로 알린다 |

## 4. 배포할 때 사람이 확인할 것

- `JWT_SECRET`은 32바이트 이상 무작위 값이어야 한다. 환경마다 따로 두고, 저장소나 채팅에 남기지 않는다.
- HTTPS는 앞단 프록시에서 끝내고 `X-Forwarded-Proto: https`를 넘긴다. 그래야 갱신 토큰 쿠키에 Secure가 붙는다. Tomcat이 그 프록시를 내부 프록시로 믿는지도 확인한다(기본은 사설 대역).
- `APP_CORS_ALLOWED_ORIGINS`, `FRONTEND_URL`, `APP_OAUTH2_REDIRECT_URI`에는 운영 주소만 넣는다.
- 앱을 `dev`·`test` 프로필로 띄우지 않는다. 띄우면 데모 계정이 생긴다. 떠 있는 DB에 `demo-owner`가 없는지도 본다.
- Redis가 꺼지면 속도 제한과 잠금도 같이 꺼진다. Redis는 앱과 같이 감시한다.
- 로그인 실패 429와 403이 갑자기 늘면 알리게 한다. 기준은 파일럿 첫 주의 기록을 보고 정한다.
