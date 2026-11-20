---
name: flowmat-editor-release
description: FlowMat이 쓰는 편집기(flowmat-editor)를 바꿀 때. 정본은 Dev-Forge-Group/flowmat-editor이고 FlowMat 안의 사본은 고치지 않는다.
---

# 편집기 바꾸기

1. 정본은 `Dev-Forge-Group/flowmat-editor`다. FlowMat의 `flowmat_frontend/src/lib/flowmat-editor`는 릴리스 사본이고, `flowmat_frontend/src/lib/flowmatEditorCopy.test.ts`가 그 내용의 해시와 맞는 릴리스를 적어 둔다.
2. 순서:
   1. 편집기 저장소에서 고치고 그 저장소의 테스트를 통과시킨다.
   2. 버전을 낸다(태그와 패키지). 패키지 발행은 사람이 승인한다.
   3. FlowMat에서 사본을 그 릴리스로 바꾸거나, 패키지로 옮긴 뒤에는 의존 버전을 올린다. 사본을 바꿨으면 `flowmatEditorCopy.test.ts`의 릴리스 이름과 해시를 같은 변경에서 고친다.
   4. FlowMat에서 `npm run lint`, `npm run typecheck`, `npm test`, `npm run build`와 편집기 관련 모의 E2E(`e2e/canvas-*.spec.ts` 등)를 돌린다.
3. 사본만 고쳐서 FlowMat의 문제를 푸는 것은 하지 않는다. 급하면 편집기 저장소에 고친 것을 먼저 넣고 같은 날 릴리스한다.
4. 편집기 밖(`flowmat_frontend/src/pages`, `src/features`)에서 편집기를 감싸는 코드는 FlowMat에서 바로 고친다.
