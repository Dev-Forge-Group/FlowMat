# archify 뷰어 기능 대조

> **현행 문서** · 확인 2026-11-14(archify `a406594`, FlowMat `main` `4c8d715`) · 이식하거나 가져오지 않기로 정하면 같은 변경에서 고친다.

[월간 개발 계획](../status/MONTH-PLAN-2026-11-16.md)의 "archify 온전 이식"은 이 표의 모든 줄이 **있음** 또는 **가져오지 않음(이유)**이 되면 끝난다. 기준은 archify README의 "Explore and share the output" 표와 `viewer/` 모듈 14개다. FlowMat 쪽은 `flowmat_frontend/src/pages/workspace/`의 코드로 확인했다.

archify는 Apache-2.0이다. 가져온 코드와 스타일의 고지는 [THIRD_PARTY_NOTICES](../../THIRD_PARTY_NOTICES.md)에 있다.

## 대조표

| archify 기능 | archify 위치 | FlowMat | 상태 |
|---|---|---|---|
| 다이어그램 안내(`?`) | `guided-views.js` 밖, 셸 | `DiagramGuide` | 있음 |
| 공정 찾기(`/`) | `node-finder.js` | `ProcessFinder` | 있음 |
| 위·아래 도달 추적과 링크(`#focus=…&reach=…`) | `focus.js` | `SemanticPassport`, `readingHash` | 있음 |
| 경로 찾기·단계 재생(`R`, `#route=a~b`) | `route-probe.js` | `RouteProbe` | 있음 |
| 종류 렌즈, 두 종류 비교(`L`, `#lens=a~b`) | `semantic-lens.js` | `SemanticLens` | 있음 |
| 개요 레이더(`M`) | `semantic-radar.js` | `SemanticRadar` | 있음 |
| 발표 무대(`F`) | 셸 | 보기 도구의 무대, `?present=1` | 있음 |
| 시각 스타일(`S`) | 셸 | `StylePresetMenu` | 있음 |
| 줌·초기화(`+` `-` `0`) | `viewer-camera.js` | 보기 도구 | 있음 |
| 미리 보기(Intent Trace) | `intent-trace.js` | 캔버스 | 있음 |
| 움직임 Live/Still, 동작 줄이기 | `motion-governor.js` | 보기 도구 | 있음 |
| 패스포트 이동·연결 목록·따라가기 | `focus.js` | `SemanticPassport`, `ProcessConnections` | 있음 |
| 연결 하나를 고정하고 링크로 남기기(`#relation=<id>`) | `focus.js` | 패스포트 연결 줄의 고정 단추, `readingHash`의 `relation` | 있음(2026-11-14) |
| **내보내기 메뉴(`E`)** | `export.js` | 리본에 PNG·SVG 내보내기만 있다 | 일부 |
| └ PNG를 클립보드로 | `export.js` | | 없음 |
| └ SVG 밝게·어둡게·자동 | `export.js` | SVG 한 가지(`workflowSvg`) | 일부 |
| └ 경로·도달 공유 카드 | `export.js` | | 없음 |
| └ 6초 움직임 WebM | `export.js` | | 없음 |
| **공정 목록(Node index)과 옆 패널** | `node-outline.js`, 셸의 rail | | 없음 |
| **안내 보기(Guided views: 장마다 보기·재생·자막·장 링크)** | `guided-views.js` | | 없음 |
| 테마 전환 | 셸(`T`) | 머리글의 `ThemeModeControl` | 있음. `T` 단축키는 가져오지 않음: FlowMat에서 `T`는 글상자 도구다 |
| 패스포트의 소스 근거(검증된 저장소·파일 링크) | `focus.js` | | 가져오지 않음: FlowMat 공정에는 소스 근거가 없다. 공정의 설명·작업 지침이 같은 자리에 맞으면 따로 정한다 |
| 트리 펼치기·접기 | `tree-branches.js` | | 가져오지 않음: 트리 다이어그램 전용이고 FlowMat 그래프는 트리가 아니다 |
| 넓이에 맞춘 읽기 배치(요약 카드를 다이어그램 옆으로) | `reader-layout.js`, `viewer-chrome-layout.js` | | 가져오지 않음: FlowMat은 편집기 배치(왼쪽 팔레트·오른쪽 인스펙터)를 쓴다 |
| 화면 문구 7개 언어 | `archify/locales/` | 영어 | 가져오지 않음: 뷰어 기능이 아니라 제품 전체의 다국어 결정이다 |

## 이식 순서 (2~4주차)

FlowMat의 데이터에 그대로 맞는 것부터, 화면만 바뀌는 것부터 한다.

1. ~~`#relation=<connectionId>`~~ **끝(2026-11-14).** 패스포트의 연결 줄 옆 고정 단추로 고정하면 그 연결과 양 끝만 보이고 주소에 남는다. 링크로 열면 시작 공정의 패스포트가 그 연결을 고정한 채 열린다. archify는 캔버스의 연결을 눌러 고정하지만, FlowMat에서 연결을 누르면 편집용으로 선택되므로 고정은 패스포트에서 한다.
2. **내보내기 메뉴(`E`).** 보기 도구에 메뉴를 두고 리본의 PNG·SVG를 같이 부른다. PNG 클립보드 복사, SVG 밝게·어둡게를 더한다. 경로·도달 공유 카드는 그다음이다.
3. **공정 목록과 옆 패널.** archify는 레인·단계로 묶는다. FlowMat에는 레인이 없으니 공정 종류로 묶고, 항목을 누르면 그 공정을 읽는다.
4. **안내 보기.** 장 목록을 워크플로에 저장해야 하므로 서버 모델이 필요하다. 저장 위치(워크플로 판에 묶을지, 편집 문서에 둘지)를 ADR로 먼저 정한다.
5. **6초 WebM.** 브라우저 `MediaRecorder`가 필요하고 움직임이 Live일 때만 뜻이 있다. 마지막에 한다.

각 항목은 브라우저 테스트와 함께 들어가고, 들어가면 위 표의 상태를 **있음**으로 고친다.
