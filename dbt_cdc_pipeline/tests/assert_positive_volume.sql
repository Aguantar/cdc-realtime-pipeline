-- 거래량/거래대금 양수 검증
-- staging에서 필터링했지만, 집계 후 재검증
SELECT *
FROM {{ ref('int_ohlcv_1h') }}
WHERE volume <= 0
   OR amount <= 0
   OR trade_count <= 0
