# ADR-030: 얼굴 로그인 기본 OFF

작성일: 2026-11-01

## 상태

**Accepted (2026-11-01, 사용자 최종 결정 D4 "Production 기본 OFF / 기능 격리"). 로컬 구현(2026-11-01, 마이그레이션 없음, 커밋 전).**

근거: 2026-11-01 전수조사 D4, 사용자 결정표([WORKBOARD](../../status/WORKBOARD.md) §3 "2026-11-01 사용자 최종 결정").

## 배경

- `/auth/face/count·register·match·select·login`은 브라우저가 만든 128차원 얼굴 벡터를 자격 증명으로 쓴다. 벡터는 사진으로도 만들 수 있고 생체 확인(liveness)이 없다. 막는 장치는 IP당 속도 제한과 5회 실패 잠금뿐이다.
- 프런트에는 얼굴 로그인 화면이 없다(2026-11-01 확인: `flowmat_frontend/src`·`e2e`에서 `/auth/face` 호출 0건). 그래서 끄더라도 화면 동작은 바뀌지 않는다.
- 등록된 얼굴 벡터는 `face_descriptor` 표에 있다.

## 결정

1. 얼굴 로그인은 기본으로 꺼진다: `app.auth.face-login.enabled: ${APP_AUTH_FACE_LOGIN_ENABLED:false}`. 운영·개발·테스트 모두 같다.
2. 꺼져 있으면 `/auth/face/**`의 모든 경로·메서드가 404 "Face login is not enabled."(`FACE_LOGIN_DISABLED`)로 답한다([`FaceLoginDisabledController`](../../../flowmat_backend/modules/platform/src/main/java/org/myweb/flowmat/domain/user/api/FaceLoginDisabledController.java)). 등록·매칭·로그인·개수 조회가 모두 막혀, 새 얼굴 자료를 받지도 비교하지도 않는다. 로그인이 필요한 `count`·`register`는 로그인하지 않은 요청에 지금처럼 401이 먼저 난다.
3. 켜는 길은 명시적 설정(`APP_AUTH_FACE_LOGIN_ENABLED=true`)뿐이고, 켜면 지금 동작([`FaceAuthController`](../../../flowmat_backend/modules/platform/src/main/java/org/myweb/flowmat/domain/user/api/FaceAuthController.java)) 그대로다. 코드는 지우지 않는다.
4. 이미 저장된 `face_descriptor` 행은 지우지 않는다.
5. 운영에서 다시 켜기 전에 검토한다: 실제 제품 요구, 생체 자료 처리(저장 여부, 원본 이미지와 템플릿), 전송 보안, 사용자 동의, 대체 인증, 위조·liveness 대응, 감사·개인정보 요구. 이 검토 전에는 운영에서 켜지 않는다.

## 수용 기준

- [x] 기본 설정에서 `/api/auth/face/login`·`match`·`select`가 404 `FACE_LOGIN_DISABLED`이다([`ProductionDefaultsIntegrationTest`](../../../flowmat_backend/src/test/java/org/myweb/flowmat/ProductionDefaultsIntegrationTest.java)). 기본값이 false다([`ProductionConfigurationTest`](../../../flowmat_backend/src/test/java/org/myweb/flowmat/global/config/ProductionConfigurationTest.java)).
- [x] 얼굴 로그인이 꺼진 상태에서 일반 로그인·JWT·로그인 속도 제한·인증 보안 회귀(`AuthSecurityIntegrationTest`, `LoginRateLimit*`)와 얼굴 서비스 테스트(`FaceAuthServiceImplTest`, `FaceAuthenticationIntegrationTest`)가 통과한다(2026-11-01, 깨끗한 사본 9클래스 27건).

## 결정하지 않은 것

- 저장된 얼굴 벡터를 지울지와 보존 기간. 개인정보 결정이라 사용자 몫이다.
- 다시 켤 때의 설계(위 5번 검토).

## 결과

- 일반 로그인, OAuth, JWT, 프로젝트 권한, `SecurityConfig`의 필터 체인은 바뀌지 않았다.
- 기존 `FaceAuthenticationIntegrationTest`는 서비스를 직접 부르므로 설정 없이 그대로 돈다.
