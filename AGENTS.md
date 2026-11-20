# 에이전트 지침

Claude Code, Codex 등 AI 코딩 도구가 이 저장소에서 작업할 때 따른다.

## 커밋·PR 작성자 표기

GitHub contributors에 AI 도구 계정이 올라가지 않도록 커밋 메타데이터에 AI 도구를 남기지 않는다.

- 커밋 메시지와 PR 본문에 AI 도구를 공동 작성자로 넣지 않는다. `Co-authored-by: Claude …`, `Co-authored-by: Codex …`처럼 `noreply@anthropic.com`·`openai.com` 주소를 쓰는 트레일러가 대상이다.
- AI 도구의 세션 링크도 넣지 않는다. `Claude-Session:` 트레일러와 PR 본문의 claude.ai 세션 URL, "Generated with …" 문구가 대상이다.
- 커밋의 author와 committer는 작업을 맡긴 사람의 Git 계정으로 한다. 실행 환경의 Git 사용자가 AI 도구 계정(예: `Claude <noreply@anthropic.com>`)이면 커밋 전에 저장소 설정(`git config user.name`, `git config user.email`)으로 바꾼다.
- Claude Code는 `.claude/settings.json`의 `attribution` 설정으로 공동 작성자 표기와 세션 링크를 끈다.
- `.github/workflows/commit-attribution.yml`이 PR과 기본 브랜치 push에서 이를 검사한다. 걸리면 해당 커밋의 트레일러나 작성자를 고친 뒤 다시 push한다.

## 정본과 고치지 않는 곳

- 제품 코드의 정본은 `flowmat_frontend/`와 `flowmat_backend/`다. 루트의 오래된 Gradle 골격과 `legacy/`를 새 애플리케이션으로 보지 않는다.
- `flowmat_backend/modules/<context>`는 한 제품의 Gradle 모듈이다. 폴더마다 `git init`하지 않는다. 모듈 경계는 `modules.yml`, [domain-map](docs/architecture/domain-map.md), ADR, 실제 빌드와 DB 외래 키를 함께 본다. YAML만 바꿔서는 도메인이 나뉘지 않는다.
- 편집기의 정본은 `Dev-Forge-Group/flowmat-editor`다. `flowmat_frontend/src/lib/flowmat-editor`는 릴리스 사본이라 여기에 기능을 더하지 않는다. 편집기 저장소에서 고치고 버전을 낸 뒤 이 사본(또는 패키지)을 바꾼다. `flowmatEditorCopy.test.ts`가 사본의 해시를 고정해 둔다.
- 분리 후보 저장소(`flowmat-compute`, `flowmat-erp`, `flowmat-mes`, `flowmat-crm`, `flowmat-ai`)가 있다고 구현이 옮겨진 것은 아니다. 지금 무엇이 어디서 도는지는 [CURRENT_CAPABILITIES](docs/status/CURRENT_CAPABILITIES.md)와 [분리 후보](docs/architecture/extraction-candidates.md)로 확인한다. 보관 문서의 할 일보다 현행 ADR과 이 두 문서를 믿는다.
- Rust 등 다른 언어의 계산은 같은 입력으로 Java와 답을 비교하는 실험이다([compute](compute/condition-expression/README.md)). 벤치마크와 계약 테스트를 통과하기 전에는 Java·TypeScript 정본을 바꾸지 않는다.
- 저장소의 `.gitignore`, 추적되는 생성물, 브랜치 구조를 에이전트 작업을 쉽게 하려고 바꾸지 않는다.

## 원장과 DB

- 재고 원장의 쓰기는 inventory의 공개 명령 하나로만 한다. ERP·MES(`inventory`, `production`, `quality`, `purchasing`)는 외래 키와 원장 책임이 맞물려 있어, 저장소나 서비스를 나누면서 원장 쓰기를 두 곳에 만들지 않는다. 나누려면 스키마·API·트랜잭션 이전 계획이 먼저다.
- 재고·생산을 바꾸면 revision 고정, 재전송 멱등성, 단위·정밀도, LOT 계보, 재고 합계 = 원장 합계를 지킨다. 물리 생산과 시뮬레이션 실행을 섞지 않는다.
- 앱을 띄우면 Flyway가 바로 마이그레이션한다. 개발 DB(5434)나 운영 DB에 붙여 앱을 띄우지 않는다. 그 DB의 마이그레이션 적용은 사람이 [절차](docs/operations/backup-restore.md) §5로 한다. 테스트는 Testcontainers의 버리는 DB를 쓴다. `scripts/ops/*-rehearsal.sh`는 버리는 복제 DB에서만 돌린다.
- 이미 `main`에 들어간 마이그레이션 파일은 고치지 않고 새 번호를 더한다.
- 비밀값(`flowmat_backend/.env` 등, `*.pem`, `*.key`)을 읽거나 출력하거나 커밋하지 않는다. 설정의 모양은 `.env.example`로 본다. 실제 고객 자료를 프롬프트·로그·스크린샷에 넣지 않는다.

## 검증

가장 작은 관련 검사부터 돌리고, 그다음 영향받는 통합 테스트를 돌린다. 검사를 끄거나(`skip`, `@Disabled`, `continue-on-error`) 단정을 지우거나 권한을 넓혀서 초록을 만들지 않는다. 실패는 로그와 재현으로 원인을 먼저 본다. 재시도로 통과한 것은 불안정한 테스트로 기록한다.

```bash
cd flowmat_frontend && npm ci && npm run lint && npm run typecheck && npx tsc --noEmit -p tsconfig.e2e.json && npm test && npm run build
```

```bash
cd flowmat_backend && ./gradlew test jacocoTestCoverageVerification && ./gradlew build -x test
```

- CI는 이미 있는 워크플로가 판정한다: `backend.yml`, `frontend.yml`, `mock-e2e.yml`, `browser-e2e.yml`, `compute.yml`, `security.yml`, `commit-attribution.yml`. 새 범용 워크플로로 중복하지 않는다. 경로 조건 때문에 새 파일이 검사에서 빠지지 않는지 본다.
- 프런트만의 성공을 전체 통과로 적지 않는다. 서버와 함께 도는 확인은 `browser-e2e.yml`(실제 Postgres·Redis)이다.
- 릴리스 후보는 [점검표](docs/status/RELEASE-CHECKLIST.md)가 같은 커밋에서 모두 통과해야 한다. 초록 배지 하나로 대신하지 않는다.
- JaCoCo 기준(줄 45%, 분기 30%)을 낮추지 않는다.

## 위험 등급과 사람의 승인

| 등급 | 예 | 에이전트가 혼자 하는 것 | 사람의 승인이 필요한 것 |
|---|---|---|---|
| 낮음 | 문서, 읽기 전용 분석 | 읽기·고치기·정적 검사 | 커밋·PR·push는 아래 규칙대로 |
| 중간 | 화면 하나, API 하나의 내부 정리 | 그 파일 고치기와 단위 테스트 | 외부 API 계약이나 배포가 생기면 |
| 높음 | 재고·원장, 인증·권한, 결제, 마이그레이션 | 읽기, 계획, 격리된 테스트 | 코드 반영, 데이터 변경, merge |
| 치명 | 운영 DB, 운영 설정·배포, 비밀값, 대량 삭제 | 계획과 해가 없는 확인 | 정확한 대상과 되돌리는 법을 확인한 뒤의 실행 |

- 커밋·push·PR·merge는 사람이 그 범위를 맡겼을 때만 한다(세션에서 "PR·push·merge를 맡긴다"고 한 것도 맡긴 것이다). force push, 다른 사람 브랜치의 이력 변경, 브랜치 삭제는 따로 승인받는다.
- 운영 설정과 배포, 운영 DB의 마이그레이션은 정확한 대상에 대한 별도 승인 없이 하지 않는다.
- 한 에이전트는 한 브랜치(필요하면 한 worktree)에서 한 가지를 한다. 다른 에이전트가 고치던 파일은 건드리지 않는다. 같은 원장 파일을 ERP와 MES 작업이 동시에 고치지 않는다. 여럿이 돌 때는 DB·Redis·포트·`docker compose -p` 이름을 나눈다.

## 끝난 기준

- 맞는 저장소·브랜치·정본을 고쳤다.
- 바뀐 동작, 바뀌지 않아야 할 동작, 회귀를 테스트로 정했고 실제로 돌렸다. 돌리지 못한 검사는 그렇다고 적었다.
- API·DB·상태·권한·단위·수량 불변식을 확인했다.
- `git diff --check`가 깨끗하고, 비밀값·임시 산출물·거짓 성공 표기가 없다.
- 코드에 맞춰 문서·계약·테스트를 같은 변경에서 고쳤다.
- 영향받는 CI가 같은 SHA에서 통과했고, 필수 job이 `skipped`나 `continue-on-error`로 비켜 가지 않았다.

## 작업별 절차(Skills)

자세한 절차는 필요할 때만 읽는다. `.claude/skills/`에 있다.

| 스킬 | 언제 |
|---|---|
| `flowmat-preflight` | 큰 작업을 시작할 때: 정본, 영향, 위험 등급, 검사 계획 |
| `flowmat-journey` | 구매·창고·BOM·생산·품질을 바꿀 때 |
| `flowmat-module-boundary` | 모듈 경계, 공개 계약, 분리 후보를 바꿀 때 |
| `flowmat-editor-release` | 편집기 사본이나 패키지를 바꿀 때 |
| `flowmat-pr-verification` | PR을 열기 전과 merge 전 |
