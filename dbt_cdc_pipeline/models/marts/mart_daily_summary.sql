{{
    config(
        materialized='table',
        order_by='day_kst, market'
    )
}}

-- 일별 종합 리포트 (종목별 고가/저가/거래량/VWAP)
-- Grafana 일별 코인 시세 대시보드 데이터소스
SELECT
    d.market,
    d.day_kst,
    d.open,
    d.high,
    d.low,
    d.close,
    d.volume,
    d.amount,
    d.trade_count,
    d.bid_count,
    d.ask_count,
    d.vwap,
    d.daily_range_pct,
    -- 매수/매도 비율
    if(d.trade_count > 0,
       round(d.bid_count / d.trade_count * 100, 1),
       0
    ) AS bid_ratio_pct,
    -- 전일 대비 종가 변동률
    if(prev.close > 0,
       round((d.close - prev.close) / prev.close * 100, 2),
       0
    ) AS close_change_pct,
    -- 전일 대비 거래량 변동률
    if(prev.volume > 0,
       round((d.volume - prev.volume) / prev.volume * 100, 2),
       0
    ) AS volume_change_pct
FROM {{ ref('int_ohlcv_daily') }} AS d
LEFT JOIN {{ ref('int_ohlcv_daily') }} AS prev
    ON d.market = prev.market
    AND d.day_kst = prev.day_kst + 1
ORDER BY d.day_kst DESC, d.amount DESC
