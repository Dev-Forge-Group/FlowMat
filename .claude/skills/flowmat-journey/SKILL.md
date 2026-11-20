---
name: flowmat-journey
description: FlowMat의 구매→입고→적치→반품과 BOM→작업지시→할당→피킹→실행→검사→부적합→계보·원가 여정을 바꿀 때, 원장·재전송·단위·LOT 불변식을 기존 통합 테스트와 실 API 브라우저 테스트로 확인한다.
---

# FlowMat 업무 여정 확인

1. [릴리스 점검표](../../../docs/status/RELEASE-CHECKLIST.md) §2, [domain-map](../../../docs/architecture/domain-map.md), 바꾸는 곳의 `docs/domain/` 계약을 읽는다.
2. 기대 결과를 숫자로 적는다: 품목·위치·LOT별 재고, 원장 이동의 합, 상태 전이. 끝에서 재고 합계 = 원장 합계를 확인한다.
3. 다음을 테스트로 덮는다.
   - 같은 요청의 재전송(`requestId`), 응답을 잃은 뒤 다시 보내기: 한 번만 기록된다.
   - 부분 진행(부분 입고·부분 적치·부분 반품), 승인 단계, 취소.
   - 동시에 같은 재고를 쓰는 요청(행 잠금 순서).
   - 단위 환산과 소수 정밀도, LOT 계보, revision 고정.
   - 다른 프로젝트·역할에서의 접근 차단(403·404).
4. 서버 테스트를 먼저 돌린다.

```bash
cd flowmat_backend && ./gradlew test --tests '*PurchaseWarehouseJourneyIntegrationTest' --tests '*ProductionQualityJourneyIntegrationTest' --tests '*RunItemRequestReplayIntegrationTest'
```

5. 화면 쪽은 실 API 스펙 `purchase-warehouse-live.spec.ts`, `production-quality-live.spec.ts`다. 실제 Postgres·Redis가 필요해 보통 `browser-e2e.yml`에서 돈다. 로컬에서 돌리려면 버리는 DB를 쓴다.
6. 개발 DB(5434)나 오래 쓰는 DB에 붙이지 않는다. 편집기 사본도 고치지 않는다.
7. 결과에 돌린 명령, 실패한 단정, 더한 마이그레이션 번호, 검사한 커밋 SHA를 적는다.
