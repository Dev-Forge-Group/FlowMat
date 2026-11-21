---
name: flowmat-pr-verification
description: FlowMat에서 PR을 열기 전과 merge 전에 돌릴 검사, 작성자 표기, CI 확인 순서. push·merge를 맡지 않았으면 검사와 보고까지만 한다.
---

# PR 전 확인

1. 바뀐 것에 맞는 검사를 고른다.
   - 백엔드: 바꾼 곳의 통합 테스트, 모듈 검사(`*Module*Test`), 마이그레이션을 더했으면 `*Migration*` 테스트. 넓게 바꿨으면 `./gradlew test jacocoTestCoverageVerification`.
   - 프런트: `npm run lint`, `npm run typecheck`, `npx tsc --noEmit -p tsconfig.e2e.json`, `npm test`, 바뀐 화면의 모의 E2E(`npm run test:e2e -- e2e/<spec>.spec.ts`). 새 API를 부르면 다른 스펙의 엄격한 가짜 응답(`auth.spec.ts` 등)에도 그 경로를 더한다.
   - compute: `cargo test`, `compute/flow-kernel/wasm/build.sh --check`(편집기가 싣는 모듈), `node wasm/check-fixtures.mjs`.
2. `git diff --check`를 깨끗하게 하고, 비밀값·임시 파일·빌드 산출물이 들어가지 않았는지 본다.
3. 작성자 표기([AGENTS.md](../../../AGENTS.md) 첫 절): author와 committer는 맡긴 사람의 계정이고, AI 공동 작성자 트레일러와 세션 링크가 없다. PR 본문에도 넣지 않는다.
4. 문서: [CURRENT_CAPABILITIES](../../../docs/status/CURRENT_CAPABILITIES.md), [WORKBOARD](../../../docs/status/WORKBOARD.md), 해당 `docs/domain/` 계약과 ADR을 같은 변경에서 고쳤다.
5. PR 본문에는 무엇을·왜, 돌린 검사와 결과, 돌리지 못한 검사, 남은 일을 적는다.
6. merge 전: 그 PR의 최신 SHA에서 영향받는 워크플로가 모두 통과했는지 본다. `skipped`는 경로 조건 때문인지 확인하고, 실패를 재실행만으로 넘기지 않는다. merge는 맡은 경우에만 한다.
