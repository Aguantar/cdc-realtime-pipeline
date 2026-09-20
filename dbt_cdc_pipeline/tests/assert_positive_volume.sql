-- 거래량/거래대금 양수 검증
-- staging에서 필터링했지만, 집계 후 재검증
-- 2026-09-20 발견(docs/34 #3): MySQL trade_amount 가 DECIMAL(20,4) 라 가격×수량 < 0.00005 KRW 인 먼지 체결(30일 64,669행·252마켓)은 amount 0 으로 저장된다.
-- 금액 정밀도는 #5(Decimal) 에서 고친다. 그 전까지 amount=0 은 volume*가격이 0.0001 미만일 때만 허용 — 진짜 0 가격·0 수량은 여전히 잡는다.
SELECT *
FROM {{ ref('int_ohlcv_1h') }}
WHERE volume <= 0
   OR trade_count <= 0
   OR (amount <= 0 AND volume * close >= 0.0001)
