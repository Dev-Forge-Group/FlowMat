# 릴리스 후보 점검표

> **현행 문서** · 확인 2026-11-22(실행 조건과 의존성 감사 판정). 검사를 더하거나 빼면 같은 변경에서 고친다.

[월간 계획](MONTH-PLAN-2026-11-16.md) 3주차 3번이다. 한 커밋을 릴리스 후보로 부르려면 아래가 **그 커밋에서** 모두 통과해야 한다. 다른 커밋의 결과는 가져오지 않는다. 통과하면 4주차에 태그를 달고 [CURRENT_CAPABILITIES](CURRENT_CAPABILITIES.md)를 그 커밋 기준으로 맞춘다.

## 1. 자동 검사 (GitHub Actions)

그 커밋에서 아래 워크플로를 `workflow_dispatch`로 돌려 모두 초록이어야 한다. merge는 검사를 다시 돌리지 않고, PR은 이미 통과한 E2E 명세를 건너뛴다. 손으로 돌리면 빼는 것 없이 다 돈다([CI가 다시 돌리지 않는 것](../operations/ci-selection.md)).

| # | 검사 | 워크플로 | 통과 기준 |
|---|---|---|---|
| 1 | 백엔드 전체 테스트 | `backend.yml` | `./gradlew test` 실패 0, `jacocoTestCoverageVerification` 통과, `build -x test` 통과 |
| 2 | 프런트 정적 검사와 단위 테스트 | `frontend.yml` | `npm run lint`, `tsc`(앱·e2e), `npm test`, `npm run build`, 로그인 E2E 2개 |
| 3 | 모의 API 브라우저 테스트 | `mock-e2e.yml` | 세 조각 모두 실패 0(재시도로 통과한 것은 원인을 적는다) |
| 4 | 실 API 브라우저 테스트 | `browser-e2e.yml` | 모든 단계 통과. 두 여정의 `purchase-warehouse-live`, `production-quality-live`가 들어 있다. 이 워크플로는 스펙을 하나씩 적으므로, 새 실 API 스펙은 단계를 더해야 돈다. `labor-premium-cost-real-api`만 빠져 있다: 일회용 환경 전용 표시(`FLOWMAT_*_E2E_DISPOSABLE`)가 있어야 도는 스펙이다 |
| 5 | 작성자 표기 | `commit-attribution.yml` | 통과([AGENTS.md](../../AGENTS.md)) |
| 6 | 의존성 감사 | `security.yml` | `npm audit --audit-level=moderate` 0건. OWASP 요약(List findings)에 새 높음·치명 항목 없음. 남기는 항목은 이유를 적는다. OWASP가 `Vulnerability data unavailable`로 실패하면 발견이 아니라 돌지 못한 것이다: 칸에 "미실행(NVD)"을 적고 후보 태그는 단다. 배포 결정 전에는 키(`NVD_API_KEY`, 활성화한 API 키)나 NVD를 고쳐 돌린 결과가 있어야 한다. 키가 없으면 첫 동기화가 오래 걸린다(그다음은 캐시) |

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
| 2026-11-22 | `main` `4d08345` | 1~5 통과(손으로 돌린 실행: backend 267, frontend 277, mock-e2e 80, browser-e2e 260 명세 43개, compute 46, sequence-optimizer 9, 작성자 표기 push 178). 6: npm audit 0건, OWASP 미실행(NVD): NVD가 `NVD_API_KEY`를 거절했다(run 233, 활성화한 API 키가 아님) | 통과(백엔드의 두 여정 테스트, 실 API `purchase-warehouse-live`·`production-quality-live`) | 통과(지문 같음, 적용 0, 여정 재고가 DB와 같음) | 통과 V134 → V137(빈 표 12개만 삭제, 다시 띄우면 up to date) | `a30ff06863a0`(설정 6개) | 전개 37 ms, MRP 43 ms, 시뮬레이션 15 ms(기록 표 마지막 줄 43·59·17 ms) | 후보 `v0.9.0-rc.1`. 배포 결정 전에 OWASP를 돌린다 |
