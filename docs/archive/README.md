# 보관 문서 (레거시)

끝난 계획, 대체된 설계, 인계, 감사, 교대 기록, 조사 자료다. **현재 기준이 아니다.** 구현·리팩토링으로 사실과 달라진 내용이 섞여 있으므로 작업 근거로 쓰지 않는다. 지금 기준은 [docs/README.md](../README.md)에서 시작한다.

모든 보관 문서 맨 위에 `보관 문서(레거시)` 표시가 있다. 검색으로 파일을 바로 열었을 때도 현행이 아님을 알 수 있게 하려는 것이다.

| 폴더 | 내용 | 대신 볼 현행 문서 |
|---|---|---|
| [2026-07-editor-research/](2026-07-editor-research/) | 캔버스 라이브러리 조사(xyflow·tldraw·excalidraw·yjs·instldraw·tldraw-sync-cloudflare). 팀원별 폴더 `hj`·`jb`·`nekopunch`·`seolly`이며, `seolly`가 이식 가이드까지 담아 가장 자세하다. 엔진 지시서와 2026-08 자유 캔버스 계획이 이 문서들을 근거로 인용한다 | [editor/current-state](../editor/current-state.md), [editor/ADR-0001](../editor/adr-0001-flowmat-editor-core-boundary.md) |
| [2026-07-status/](2026-07-status/) | 7월 협업·프런트 상태 기록(WebSocket 협업, 역할·RBAC, 초대·알림) | [status/CURRENT_CAPABILITIES](../status/CURRENT_CAPABILITIES.md) |
| [2026-08-editor/](2026-08-editor/) | 8월 에디터 계획(프로젝트 테이블 개선 기획안, 구현 백로그, annotation·자유 캔버스 계획), 진행 기록([커넥터](2026-08-editor/connector-line-progress-2026-08-14.md), [8월 current-state 원본](2026-08-editor/editor-current-state-2026-08.md)), 교대 기록 [relay/](2026-08-editor/relay/BATON.md)(2026-08-18에 멈춤) | [editor/current-state](../editor/current-state.md), [architecture/decision-handoff](../architecture/decision-handoff.md) |
| [2026-09-architecture/](2026-09-architecture/) | 2026-09-24 판 도메인 지도(`enterprise-domain-map`), 실행 모델(`execution-model`), 로드맵(`domain-roadmap`). 2026-10-02에 현행 판으로 대체 | [architecture/domain-map](../architecture/domain-map.md), [architecture/execution-model](../architecture/execution-model.md), [status/WORKBOARD](../status/WORKBOARD.md) |
| [2026-09-handoff/](2026-09-handoff/) | 2026-09-23·24 작업 분할, 위임 지시서, 보고서 A–J, 인증 refresh 경합·V21 공지, 2026-09-24 코드 감사 | [status/CURRENT_CAPABILITIES](../status/CURRENT_CAPABILITIES.md), 열린 결정은 [status/WORKBOARD](../status/WORKBOARD.md) |
| [2026-10-workboard/](2026-10-workboard/) | 2026-10-09~10-31 작업 현황판의 끝난 기록: §1 진행 중 항목 52개, 리드 기록의 구현 줄 64개, §2 다음 작업 표의 끝난 줄 228개. 2026-11-01 리드가 옮겼다(번호 `2hb` 등으로 찾는다) | [status/WORKBOARD](../status/WORKBOARD.md) |

## 읽을 때 주의

- `2026-08-editor/flowmat_architecture_improvement_plan.md`의 "item을 Resource Master로 재정의"와 Resource 통합 방향은 채택하지 않았다([ADR-003](../architecture/adr/ADR-003-resource-port-contract.md) 결정 4·7). 범용 Flow Engine 지향 자체는 이어졌다.
- `2026-08-editor/flowmat_implementation_backlog.md`는 2026-09-24 감사에서 이미 과거 목록으로 판정됐다.
- `2026-09-architecture/`의 세 문서에는 2026-10-02에 넣은 "갱신" 메모가 있다. 대체되기 직전에 결정과 어긋난 줄만 고친 것이며, 본문은 2026-09 내용이다.
- 2026-09 보고서 중 이후 바뀐 것:
  - F(CI E2E)는 BOM·LOT E2E 시간 초과를 보고했다. 2026-10-02 CI는 백엔드 포함 브라우저 E2E를 통과한다.
  - auth-refresh-race 이후 `authSession`에 탭 간 refresh 잠금(`navigator.locks`)과 진행 중 refresh 공유가 들어갔다. 실 API E2E는 여전히 `workers: 1`이다.
  - flyway-v21-notice의 V21은 커밋됐다.
  - 남은 사람 결정 D1–D6은 [status/WORKBOARD](../status/WORKBOARD.md)로 옮겼다.

## 규칙

- 내용을 고치지 않는다. 고칠 수 있는 것은 맨 위 등급 표시와, 파일이 옮겨져 깨진 링크·경로 표기뿐이다.
- 현행 문서가 레거시가 되면 `archive/<YYYY-MM-주제>/`로 옮기고, 맨 위에 등급 표시를 넣고, 위 표에 한 줄을 더한다. 대체한 현행 문서에는 "이전 판" 링크를 남긴다.
- git이 추적하는 파일은 `git mv`로 옮긴다(`.gitignore`의 `*.md` 때문에 일반 `mv`는 추적을 끊는다).

## 삭제한 것 (2026-10-02)

- `docs/seolly/flowmat_architecture_improvement_plan.md`, `docs/seolly/flowmat_implementation_backlog.md`: `docs/editor/`의 같은 이름 파일과 바이트 단위로 같은 중복본이었다. 남은 사본은 `2026-08-editor/`에 있다. 원본이 필요하면 `git show 45f5df0:docs/seolly/flowmat_implementation_backlog.md`처럼 꺼낸다.
