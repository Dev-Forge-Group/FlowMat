# ADR-029: 운영 actuator를 관리 포트로 분리

작성일: 2026-11-01

## 상태

**Accepted (2026-11-01, 사용자 최종 결정 D3 "Management Port 분리"). 로컬 구현(2026-11-01, 마이그레이션 없음, 커밋 전).**

근거: 2026-11-01 전수조사 B5([WORKBOARD](../../status/WORKBOARD.md) §1 "전수조사 수정 요청"), 사용자 결정표([WORKBOARD](../../status/WORKBOARD.md) §3 "2026-11-01 사용자 최종 결정").

## 배경

- `SecurityConfig`가 `/actuator/prometheus`를 `permitAll`로 두고 `management.endpoints.web.exposure.include`에 prometheus가 있어, 운영에서도 공개 포트(`/api/actuator/prometheus`)로 경로별 요청 수·오류·JVM·DB 풀 지표가 보였다.
- 운영 설정 `application-prod.yml`은 `.gitignore`(40행)로 저장소 밖에 있다. 저장소만으로는 운영 노출을 정할 수 없었다.
- 스테이징 점검 `.github/workflows/staging-prod-migration-smoke.yml`은 공개 주소의 `/api/actuator/health/readiness`를 부른다. 관리 포트만 옮기면 이 점검과 같은 주소를 쓰는 부하 분산기 탐침이 깨진다.

## 결정

1. prod 프로필에서 actuator는 별도 관리 포트에만 둔다: `management.server.port: ${MANAGEMENT_SERVER_PORT:8081}`. 저장소에 들어가는 `application.yml`의 `on-profile: prod` 문서에 둔다. 8081은 Prometheus 서버의 기본 9090과 겹치지 않고, 저장소의 다른 설정과도 겹치지 않는다(2026-11-01 `git grep` 확인).
2. 관리 포트에는 `health`·`prometheus`만 노출한다. `info`를 포함한 나머지는 노출하지 않는다.
3. 기본 방어선은 망·포트 격리다. 관리 포트는 컨테이너 망, 방화벽·보안 그룹, 프록시 ACL로 Prometheus 수집기만 닿게 한다. 한 인터페이스에만 묶으려면 `MANAGEMENT_SERVER_ADDRESS`를 준다. 토큰 인증은 이 결정에 넣지 않는다(나중에 겹방어로 더할 수 있다).
4. 공개 포트에는 상태만 돌려주는 탐침 두 개를 남긴다: `/api/actuator/health/readiness`, `/api/actuator/health/liveness`(본문 `{"status":"UP"}`, UP이 아니면 503, 상세 없음). Spring의 health 그룹 `additional-path`는 한 단계 경로만 받으므로(`'value' must contain only one segment`, Spring Boot 3.5.15) 기존 주소를 그대로 둘 수 없다. 그래서 관리 포트가 다를 때만 생기는 `PublicHealthProbeController`(`@ConditionalOnManagementPort(DIFFERENT)`)로 둔다.
5. dev·test는 지금처럼 한 포트를 쓴다. 관리 포트 설정이 없으니 탐침 컨트롤러도 생기지 않는다.
6. 관리 포트에도 기존 보안 필터 체인이 걸린다(Spring Boot의 `ServletManagementContextSecurityConfiguration`이 부모의 `springSecurityFilterChain`을 관리 서버에 등록). `SecurityConfig`는 바꾸지 않았다.

## 수용 기준

- [x] prod 문서가 관리 포트 `${MANAGEMENT_SERVER_PORT:8081}`와 노출 `health,prometheus`를 갖는다([`ProductionConfigurationTest`](../../../flowmat_backend/src/test/java/org/myweb/flowmat/global/config/ProductionConfigurationTest.java)).
- [x] 관리 포트를 따로 연 실제 포트 테스트에서 지표는 관리 포트에서만 200이고, 공개 포트의 `/api/actuator/prometheus`는 지표를 주지 않으며, 노출하지 않은 `/actuator/env`는 200이 아니다([`ProductionDefaultsIntegrationTest`](../../../flowmat_backend/src/test/java/org/myweb/flowmat/ProductionDefaultsIntegrationTest.java)).
- [x] 같은 테스트에서 공개 포트의 readiness·liveness는 200 `{"status":"UP"}`, 공개 포트의 `/api/actuator/health`(상세)는 200이 아니다.

## 결정하지 않은 것

- 지표 경로의 토큰 인증(겹방어). 필요하면 새 ADR로 더한다.
- 배포 구성(관리 포트를 막는 망 규칙, Prometheus 수집 대상 변경)은 저장소 밖 배포 담당의 일이다.

## 결과

- 운영 배포는 관리 포트(기본 8081)를 열되 공개하지 않아야 하고, Prometheus 수집 주소를 `http://<내부 주소>:8081/actuator/prometheus`로 바꿔야 한다. 관리 포트에는 `/api` 문맥 경로가 붙지 않는다.
- 공개 포트의 `/api/actuator/prometheus`는 2026-11-01 현재 500이다(없는 경로를 500으로 만드는 전역 처리기, 전수조사 B3). B3가 고쳐지면 404가 된다. 어느 쪽이든 지표는 나가지 않는다.
- 배포처의 로컬 `application-prod.yml`이 같은 키를 주면 그 값이 이긴다(프로필 파일이 `application.yml`의 프로필 문서보다 우선).
