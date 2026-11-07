# ADR-035: bounded context별 Gradle 모듈

작성일: 2026-11-07

## 상태

**Accepted (2026-11-07).** 근거: 2026-11-07 사용자 결정("Gradle 모듈 분리 지금 진행"). 저장소를 Dev-Forge-Group으로 옮긴 뒤, ERP·MES 등 기능 묶음을 나중에 별도 저장소로 뗄 수 있게 하는 첫 단계다.

## 배경

- 백엔드는 하나의 Spring Boot 애플리케이션이고, context 경계는 패키지 규칙과 [`ModuleBoundaryTest`](../../../flowmat_backend/src/test/java/org/myweb/flowmat/architecture/ModuleBoundaryTest.java)로만 지켰다([ADR-002](ADR-002-module-dependency.md)).
- 2026-11-07에 context 사이 순환 의존 3개를 끊어 의존이 한 방향이 됐다([도메인 지도](../domain-map.md) §4). 이제 context를 빌드 단위로 나눌 수 있다.
- 저장소를 나누기 전에, 같은 저장소 안에서 빌드 단위로 먼저 나눠야 실제 결합이 드러난다. 저장소부터 나누면 결합은 남은 채 관리할 곳만 늘어난다.

## 결정

1. context마다 Gradle 모듈을 하나씩 둔다. 위치는 `flowmat_backend/modules/<context>`, Gradle 경로는 `:modules:<context>`, jar 이름은 `flowmat-<context>`다.
2. `global`(websocket 제외)과 `user`는 `modules/platform` 하나로 묶는다. 보안·권한·초기화가 `user`를 쓰고 `user`가 예외·ID를 써서 서로 떼어지지 않는다. 다른 모듈은 모두 platform에 의존하고, platform은 아무 모듈에도 의존하지 않는다.
3. 루트 프로젝트는 애플리케이션이다. `Application`, `batch`, `global.websocket`(workflow 권한을 쓴다), 설정·Flyway 마이그레이션, 모든 테스트를 그대로 둔다. CI의 `./gradlew test`, `jacocoTestCoverageVerification`, `bootRun`과 보고서 경로는 바뀌지 않는다.
4. 각 모듈은 자기 `build.gradle`에 직접 쓰는 모듈만 `implementation`으로 적는다. 적지 않은 모듈의 클래스는 컴파일되지 않으므로, 역방향 의존이나 순환은 빌드에서 바로 막힌다.
5. 외부 라이브러리는 모든 모듈이 애플리케이션과 같은 목록을 쓴다. 한 애플리케이션으로 실행되므로 모듈마다 고르지 않는다.
6. 커버리지는 루트 테스트가 모든 모듈 클래스를 측정하고, 기존 기준(줄 45%, 분기 30%)을 모듈 전체에 적용한다.
7. ERP·MES 같은 묶음 이름은 아직 모듈 이름으로 쓰지 않는다. purchasing → quality, production → purchasing처럼 묶음 사이에 걸치는 의존이 있어, 묶음을 정하려면 소유권 결정이 먼저 필요하다. [ADR-002](ADR-002-module-dependency.md) 결정 6(대형 `erp`·`mes` 패키지 금지)은 그대로다.

## 결정하지 않은 것

- 모듈을 묶어 ERP·MES 등 별도 저장소로 옮길지, 옮긴다면 어떤 묶음으로 할지
- 테스트를 각 모듈로 옮길지. 지금 통합 테스트는 대부분 애플리케이션 전체를 띄운다.
- 마이그레이션을 모듈별로 나눌지. 같은 DB를 쓰는 동안은 한 곳에서 순서를 정한다.

## 결과

- 좋은 점: 의존 방향이 빌드로 강제된다. 모듈 하나를 별도 저장소나 서비스로 떼어 낼 때 필요한 범위가 `build.gradle`에 드러난다.
- 비용: 백엔드 소스 경로가 `src/main/java/...`에서 `modules/<context>/src/main/java/...`로 바뀌어, 진행 중인 백엔드 브랜치는 병합 때 경로를 맞춰야 한다. 새 의존을 추가하려면 모듈 `build.gradle`도 고쳐야 한다.

## 검증

- `./gradlew test jacocoTestCoverageVerification` 전체 통과, 커버리지 기준 통과
- `./gradlew build -x test`의 실행 jar에 `BOOT-INF/lib/flowmat-*.jar` 15개가 들어간다.
