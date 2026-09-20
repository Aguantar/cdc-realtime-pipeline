-- 유일성 계약: (market, sequential_id) 는 업비트 체결의 자연키다. sequential_id 단독은 마켓 간 충돌 (docs/13).
-- 범위를 전날(KST)로 한정하는 이유: 전체 1억 행 유일성은 1.75GB ClickHouse 에서 매일 돌릴 수 없고,
-- 과거분은 07·13 감사에서 한 번 검증했다. 매일 새로 들어온 창만 검사하는 것이 증분 테스트의 관행.
SELECT market, sequential_id, count() AS n
FROM {{ ref('stg_trades') }}
WHERE day_kst = yesterday()
GROUP BY market, sequential_id
HAVING n > 1
SETTINGS max_memory_usage = 600000000, max_threads = 2
