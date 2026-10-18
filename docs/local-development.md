# FlowMat 로컬 실행

> **현행 문서** · 최종 확인 2026-10-02 · 실행·테스트 방법이 바뀌면 같은 변경에서 고친다. 코드 주석(`application.yml`)이 이 경로를 가리킨다.

## 준비

Java 21, Node 22, Docker Desktop을 사용한다. V2·V4·V5는 원본 파일 복원으로 해결한 이력이 있다. 기존 dev DB에는 별도로 V26 체크섬 불일치가 기록돼 있다(경위: [보관된 2026-09-24 공지](archive/2026-09-handoff/reports/flyway-v21-notice.md) "V26 checksum 불일치"). DB마다 적용 이력을 확인하고, `repair`나 기존 마이그레이션 수정을 임의로 실행하지 않는다.

`flowmat_backend/.env.example`을 `flowmat_backend/.env`로 복사하고 `POSTGRES_PASSWORD`, `DB_PASSWORD`를 같은 로컬 비밀번호로 바꾼다. `JWT_SECRET`도 32자 이상의 별도 로컬 값으로 바꾼다. `.env`는 Git에 넣지 않는다. 새 DB를 만들 때만 아래 Compose 절차를 사용한다. 기존 5432/6379 서비스가 있다면 포트 충돌부터 확인한다.

```powershell
cd flowmat_backend
Copy-Item .env.example .env
# .env의 세 비밀값을 로컬 값으로 수정
docker compose up -d postgres redis
```

## 백엔드

PowerShell은 `.env`를 자동으로 환경변수로 내보내지 않는다. 파일 값을 읽어 현재 셸에 적용한다. 이 예제는 `KEY=VALUE` 형식만 받으며 명령을 평가하지 않는다.

```powershell
Get-Content .env | Where-Object { $_ -match '^[A-Z][A-Z0-9_]*=' } | ForEach-Object {
  $key, $value = $_ -split '=', 2
  [Environment]::SetEnvironmentVariable($key, $value, 'Process')
}
.\gradlew.bat bootRun --args='--spring.profiles.active=dev'
```

`--spring.profiles.active=dev`는 빼면 안 된다. 기본 프로필이 없으므로(2026-10-02, 프로필을 잊은 운영 설치가 dev로 떠서 데모 계정이 남는 것을 막기 위함) 프로필 없이 띄우면 dev 기본값(localhost DB·Redis 등)을 쓰지 않고, 새 DB라면 V18이 데모 시드를 지운다. 그렇게 지워진 데모 시드는 아래처럼 되살아나지 않는다.

준비 상태는 `http://localhost:8080/api/actuator/health/readiness`로 확인한다. 새 dev DB에는 데모 계정 `demo-owner` / `demo1234`와 프로젝트 `prj_demo_main`이 생성된다.

기존 dev DB에서 프로젝트가 보이지 않으면 `flowmat_backend` 디렉터리에서 다음 읽기 전용 조회로 V18 적용 이력과 프로젝트 존재 여부를 확인한다. 아래 사용자명과 DB명은 `.env.example` 기본값이며, `.env`에서 바꿨다면 함께 바꾼다.

```powershell
docker compose exec postgres psql -U flowmat_dev -d flowmat -c "SELECT version, description, checksum, success FROM flyway_schema_history WHERE version IN ('2', '18', '26') ORDER BY installed_rank;"
docker compose exec postgres psql -U flowmat_dev -d flowmat -c "SELECT project_id, owner_id FROM project WHERE project_id = 'prj_demo_main';"
```

V18이 이미 데모 시드를 삭제한 DB에서는 dev 설정을 고쳐도 적용된 마이그레이션이 다시 실행되지 않아 프로젝트가 복원되지 않는다. 기존 데이터를 보존해야 한다면 별도로 검토한 복구 절차를 마련한다. 기존 개발 DB 볼륨을 삭제하거나 Flyway `repair`로 시드 복원을 시도하지 않는다.

## 프론트엔드

별도 PowerShell에서 다음을 실행한다.

```powershell
cd flowmat_frontend
npm ci
npm run dev
```

브라우저 주소는 `http://localhost:5173`이다. 변경 검증은 `npm run typecheck`, `npm run lint`, `npm test`, `npm run build`로 한다.

## 테스트

| 대상 | 명령 | 주의 |
|---|---|---|
| 백엔드 전체 | `flowmat_backend`에서 `.\gradlew.bat test` | 통합 테스트가 Testcontainers로 PostgreSQL을 띄우므로 Docker가 켜져 있어야 한다. `ModuleBoundaryTest`(ArchUnit)도 여기서 돈다 |
| 백엔드 CI와 같은 순서 | `.\gradlew.bat test`, `.\gradlew.bat jacocoTestCoverageVerification`, `.\gradlew.bat build -x test` | GitHub Actions `backend.yml`과 같다 |
| 프런트 단위 | `flowmat_frontend`에서 `npm test` | Vitest |
| 브라우저 E2E(가짜 API) | `npm run test:e2e` | 실 API가 필요한 spec 4개는 건너뛴다 |
| 브라우저 E2E(실 API) | 백엔드·Vite를 띄운 뒤 `REAL_API_E2E=1 BASE_URL=http://localhost:5173 npx playwright test` | 한 worker로 돈다. 로그인 제한(계정당 10분 8회, IP당 12회)에 걸리면 429가 나므로, 전체를 돌릴 때는 백엔드를 `AUTH_LOGIN_ACCOUNT_LIMIT`·`AUTH_LOGIN_IP_LIMIT`를 크게 준 환경변수로 띄운다 |

모듈 경계 테스트(`ModuleBoundaryTest`)는 2026-10-18부터 동결 목록 없이 돈다. 다른 도메인의 repository를 쓰면 바로 실패한다([ADR-002](architecture/adr/ADR-002-module-dependency.md), [ADR-012](architecture/adr/ADR-012-workflow-access-boundary.md)).

## 자주 나는 오류

| 증상 | 확인할 것 |
|---|---|
| `Migration checksum mismatch` | 오류에 나온 버전과 `flyway_schema_history`의 체크섬을 읽기 전용으로 비교한다. 기존 dev DB의 V26 불일치 기록은 위 인계 문서를 확인한다. DB 이력 변경은 담당자 결정 후 진행한다. |
| `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET` 누락 | `.env` 작성 뒤 백엔드 셸에 환경변수를 내보냈는지 확인한다. |
| Compose에서 `Set POSTGRES_* in .env` | `flowmat_backend/.env` 파일 위치와 세 값을 확인한다. |
| 5432 또는 6379 포트 점유 | 이미 실행 중인 DB·Redis를 확인한다. 다른 DB를 쓸 경우 `DB_URL`도 맞춘다. |
| 첫 로그인 401 | 준비 상태 직후 데모 초기화가 끝나지 않았을 수 있다. 백엔드 로그의 초기화 완료를 확인하고 재시도한다. |

임시 DB를 사용할 때는 별도 컨테이너와 포트를 만들고 `DB_URL`을 그 포트로 바꾼다. 기존 개발 DB 볼륨을 지우지 않는다.
