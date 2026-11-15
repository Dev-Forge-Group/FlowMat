# 릴리스 후보 점검표

> **현행 문서** · 확인 2026-11-14(`main` V134). 검사를 더하거나 빼면 같은 변경에서 고친다.

[월간 계획](MONTH-PLAN-2026-11-16.md) 3주차 3번이다. 한 커밋을 릴리스 후보로 부르려면 아래가 **그 커밋에서** 모두 통과해야 한다. 다른 커밋의 결과는 가져오지 않는다. 통과하면 4주차에 태그를 달고 [CURRENT_CAPABILITIES](CURRENT_CAPABILITIES.md)를 그 커밋 기준으로 맞춘다.

## 1. 자동 검사 (GitHub Actions)

`main`에 들어간 그 커밋의 실행이 모두 초록이어야 한다. 경로 조건 때문에 돌지 않은 워크플로는 `workflow_dispatch`로 그 커밋에서 직접 돌린다.

| # | 검사 | 워크플로 | 통과 기준 |
|---|---|---|---|
| 1 | 백엔드 전체 테스트 | `backend.yml` | `./gradlew test` 실패 0, `jacocoTestCoverageVerification` 통과, `build -x test` 통과 |
| 2 | 프런트 정적 검사와 단위 테스트 | `frontend.yml` | `npm run lint`, `tsc`(앱·e2e), `npm test`, `npm run build`, 로그인 E2E 2개 |
| 3 | 모의 API 브라우저 테스트 | `mock-e2e.yml` | 세 조각 모두 실패 0(재시도로 통과한 것은 원인을 적는다) |
| 4 | 실 API 브라우저 테스트 | `browser-e2e.yml` | 모든 단계 통과. 두 여정의 `purchase-warehouse-live`, `production-quality-live`가 들어 있다. 이 워크플로는 스펙을 하나씩 적으므로, 새 실 API 스펙은 단계를 더해야 돈다. `labor-premium-cost-real-api`만 빠져 있다: 일회용 환경 전용 표시(`FLOWMAT_*_E2E_DISPOSABLE`)가 있어야 도는 스펙이다 |
| 5 | 작성자 표기 | `commit-attribution.yml` | 통과([AGENTS.md](../../AGENTS.md)) |
| 6 | 의존성 감사 | `security.yml` | `npm audit --audit-level=moderate` 0건, `dependencyCheckAnalyze`(OWASP) 보고서에 새 높음·치명 항목 없음. 남기는 항목은 이유를 적는다 |

## 2. 두 여정

자동 검사 1과 4에 들어 있지만, 후보의 근거이므로 이름으로 확인한다.

| 여정 | 서버(실제 Postgres·Redis) | 화면(실 API) |
|---|---|---|
| 구매 → 입고 → 적치 → 반품 | `PurchaseWarehouseJourneyIntegrationTest` 2건 | `purchase-warehouse-live.spec.ts`: 부분 적치의 응답을 잃어도 한 번만 옮김 |
| BOM → 작업지시 → 할당 → 피킹 → 실행 → 검사 → 부적합 → 계보·원가 | `ProductionQualityJourneyIntegrationTest` 2건, `RunItemRequestReplayIntegrationTest` 3건 | `production-quality-live.spec.ts`: 투입 기록의 응답을 잃어도 한 번만 기록 |

두 여정 모두 끝에 재고 합계 = 원장 합계를 확인한다.

## 3. 손으로 돌리는 검사

| # | 검사 | 방법 | 통과 기준 |
|---|---|---|---|
| 1 | 백업·복원 | 두 실 API 여정을 돌린 DB에서 `scripts/ops/restore-rehearsal.sh`([절차](../operations/backup-restore.md)) | 지문이 같음, 마이그레이션 적용 0, API로 읽은 여정 재고가 DB와 같음 |
| 2 | 업그레이드 | 올리려는 DB의 덤프로 `scripts/ops/upgrade-rehearsal.sh`([절차](../operations/backup-restore.md) §5) | 새 마이그레이션이 모두 적용되고 실패 0, 있던 표의 행이 줄지 않고 프로젝트 합계가 같음, 다시 띄우면 "up to date" |
| 3 | 운영 프로필 설정 | `prod` 프로필 기동 검사는 자동 검사 1의 `ProductionConfigValidatorTest`, `ProductionConfigurationTest`, `ProdMigrationIsolationTest`가 한다. 스테이징이 있으면 `staging-prod-migration-smoke.yml`을 돌린다 | 약한 비밀값·localhost 주소로는 기동이 멈춤. 데모 자료가 운영 DB에 들어가지 않음 |
| 4 | 적용 설정 개정 | 후보를 띄운 로그의 `Applied runtime settings revision …` 한 줄(`AppliedSettings`) | 개정 값과 설정 목록을 릴리스 기록에 적는다. 인스턴스가 여럿이면 값이 같다 |
| 5 | 성능 | `node scripts/perf/baseline.mjs`([기준선](../operations/performance-baseline.md)) | 전개·MRP 중앙값이 기록 표의 마지막 줄보다 2배 넘게 느려지지 않음. 느려졌으면 SQL 문 수를 센다 |

## 4. 기록

후보마다 한 줄을 더한다. 통과하지 못한 칸에는 이유와 다음 조치를 적는다.

| 날짜 | 커밋 | 자동 1~6 | 두 여정 | 복원 | 업그레이드 | 설정 개정 | 성능 | 판정 |
|---|---|---|---|---|---|---|---|---|
| | | | | | | | | |
