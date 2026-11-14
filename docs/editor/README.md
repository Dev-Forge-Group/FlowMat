# 에디터

> **현행 문서** · 이 폴더의 문서는 모두 현행이다. 워크플로 화면의 캔버스·도형 편집 엔진·리본을 다룬다.

| 문서 | 내용 |
|---|---|
| [current-state.md](current-state.md) | 지금 구조: 두 층(React Flow 공정 그래프 / SVG 도형 편집), 엔진 모듈, 저장 모델 두 개, 실행 취소, 실시간 동기화, 화면, 테스트, 남은 일 |
| [adr-0001-flowmat-editor-core-boundary.md](adr-0001-flowmat-editor-core-boundary.md) | ADR-0001: 순수 TypeScript 편집 코어를 React Flow·React·API와 분리 |
| [toolbar_ribbon_migration_plan.md](toolbar_ribbon_migration_plan.md) | 상단 바 → 리본 마이그레이션 계획. Step 1~5 완료, Step 3 리뷰와 Step 6 남음. 코드 주석이 이 경로를 가리키므로 옮기지 않는다 |
| [archify-parity.md](archify-parity.md) | archify 뷰어 기능과 FlowMat 캔버스의 대조표, 남은 이식 순서 |

근거와 이전 기록:

- 엔진 구현 지시서(2026-08, 동결): [reference/editor/](../reference/editor/FlowMat_Editor_Engine_Implementation_Directive.md)
- 2026-08 계획·진행 기록·교대 기록(레거시): [archive/2026-08-editor/](../archive/README.md)
- 2026-07 캔버스 라이브러리 조사(레거시): [archive/2026-07-editor-research/](../archive/README.md)
