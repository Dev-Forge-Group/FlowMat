# ADR-044: Spring Boot 4로 올린다

작성일: 2026-11-23

## 상태

**Accepted (2026-11-23).** 사용자 결정(11-23, "1": Boot 4로 올리기)이다. 근거는 릴리스 점검표 자동 6(OWASP)의 결과다. 구현은 이 ADR과 같은 PR에 있다.

## 배경

OWASP 의존성 검사가 처음으로 끝까지 돌았다(2026-11-23, run 249). 같은 줄의 패치판으로 Tomcat·Netty·PostgreSQL·Swagger UI 등을 올린 뒤(PR #169)에도 높음·치명이 213건 남았다(CRITICAL 103, HIGH 110). 모두 Spring Framework 6.2.19와 Spring Security 6.5.11에서 나왔고, 고유 CVE는 15개다.

두 판은 각 줄의 마지막 판이다. Maven Central에 6.2.x·6.5.x의 더 새 판이 없으므로, 고치려면 Framework 7·Security 7이 필요하다. 그 둘을 관리하는 것은 Spring Boot 4다.

## 결정

1. **Spring Boot 4.1.3으로 올린다.** 이 판은 Framework 7.0.11, Security 7.1.3, Tomcat 11.0.27, Hibernate 7.4, Flyway 12.4, Testcontainers 2.0.5, JUnit 6을 관리한다.
   - 4.2.0도 GA이지만 막 나왔다. 패치가 쌓인 4.1 줄을 먼저 쓴다.
   - OWASP가 7.0.11에서도 높음·치명을 보이면 4.2로 올린다.
2. **JSON은 당분간 Jackson 2에 둔다.**
   - Boot 4의 기본은 Jackson 3(`tools.jackson`)이다. 하지만 코드(본 코드 137개, 테스트 376개 파일)가 Jackson 2(`com.fasterxml.jackson`)를 쓰고, JSON 계약(정밀도·날짜 형식)도 Jackson 2 기준으로 고정돼 있다.
   - 그래서 `spring-boot-jackson2`를 더하고, HTTP 메시지도 같은 ObjectMapper를 쓰게 한다(`spring.http.converters.preferred-json-mapper: jackson2`).
   - Jackson 2 설정 키는 `spring.jackson2.*`다. 지금은 따로 둔 설정이 없다.
   - Framework 7의 `MockMvcBuilders.standaloneSetup`은 기본으로 Jackson 3 변환기를 쓴다. 그래서 엄격한 입력 검증(소수 버전, 빈 정책 등)을 보는 단독 테스트 셋은 `MappingJackson2HttpMessageConverter`를 직접 넣는다. 다른 단독 테스트는 이미 넣고 있었다.
   - 이 설정이 없었던 첫 전체 실행에서는 343건이 실패했다. 대부분이 400 대신 200이었고, Jackson 2 전용 입력 검증이 빠졌기 때문이다. 넣은 뒤에는 0건이다.
   - Jackson 3으로 옮기는 일은 따로 한다. Boot가 Jackson 2 지원을 거두기 전에 한다.
3. **Boot 4의 나뉜 모듈과 starter를 쓴다.**
   - `spring-boot-starter-web` → `spring-boot-starter-webmvc`
   - `flyway-core` → `spring-boot-starter-flyway`. Flyway 자동 설정이 별도 모듈로 갔다.
   - 테스트에 `spring-boot-starter-webmvc-test`와 `spring-boot-micrometer-metrics-test`를 더한다.
   - Testcontainers 2의 이름(`testcontainers-postgresql`, `testcontainers-junit-jupiter`)을 쓴다.
   - springdoc은 Boot 4용 3.1.1을 쓴다.
4. **옮겨진 패키지만 바꾸고, 동작은 바꾸지 않는다.**
   - `AutoConfigureMockMvc`·`MockMvcPrint` → `org.springframework.boot.webmvc.test.autoconfigure`
   - health 액추에이터 타입 → `org.springframework.boot.health.*`
   - `ServletWebServerApplicationContext`, `ValidationAutoConfiguration`의 새 패키지
   - `@AutoConfigureObservability(tracing = false)` → `@AutoConfigureMetrics`
   - Framework 7에서 멀티파트 요청 빌더가 일반 빌더의 하위형이 아니게 됐다. 그래서 테스트 도우미가 `AbstractMockHttpServletRequestBuilder<?>`를 받게 했다.
5. **3.5에서 둔 패치 덮어쓰기(Tomcat·Netty·PostgreSQL·Jackson·commons-lang3·log4j)는 지운다.** Boot 4 BOM이 더 새 줄을 관리하기 때문이다. httpclient5·httpcore5의 짝 맞춤은 그대로 둔다.
6. **OWASP가 Boot 4에서 남긴 것을 정리한다(run 252: CRITICAL 3, HIGH 2).** Spring 6.2/6.5의 213건은 사라졌다.
   - springdoc 3.1.1이 데려오는 Swagger UI 5.32.14의 DOMPurify(CVE-2026-116803·116804): webjar를 5.35.1로 고정한다(run 249에서 깨끗했다). springdoc은 `springdoc.swagger-ui.version`으로 페이지 경로를 만들므로 `application.yml`에 같은 판을 적는다. 둘이 어긋나면 `OpenApiContractTest.swaggerUiServesThePinnedWebjar`가 실패한다.
   - spring-boot-devtools의 CVE-2022-31691: Spring Tools 편집기 확장의 CVE가 이름으로 잡힌 것이다. devtools는 `developmentOnly`라 Boot jar에 들어가지 않으므로 검사에서 그 구성을 뺀다(`skipConfigurations`). 출하물과 테스트가 쓰는 구성은 그대로 검사한다.

## 확인할 것

- 백엔드 전체 테스트, JaCoCo 기준(줄 45%·분기 30%)
- 모의·실 API 브라우저 E2E(서버 JSON이 그대로인지)
- `OpenApiContractTest`: 공개 API 계약에서 작업·스키마·필드가 사라지면 큰 판을 올려야 한다(`docs/api/README.md`).
- OWASP 재검사: Spring 6.2/6.5의 높음·치명이 사라져야 한다.
- 릴리스 점검표의 손 검사(복원·업그레이드·설정 개정·성능)를 같은 커밋에서 다시 한다.

## 하지 않은 것

- Jackson 3으로 옮기기(결정 2)
- Boot 4의 새 기능(API 판 관리, HTTP 서비스 클라이언트 등) 쓰기. 올리는 일과 섞지 않는다.
