-- ============================================
--   2층 원장 (docs/28 B): Binance Spot Testnet 주문 생애주기를 담는 트랜잭션 테이블 4개 (2026-09-19)
--   왜: 1층 체결은 INSERT 만 있어 CDC 가 큐와 같았다. 상태가 바뀌는 행(주문)을 캡처해 하류에서 최종 상태를 재구성하는 것이 2층의 목적.
--   상태를 바꾸는 주체는 거래소 매칭 엔진(실돈 없는 테스트넷). 우리가 만든 것은 주문 규칙뿐.
--   파티션 없음: 하루 수백 행. 시각 포함 키(A 설계)는 파티션 프루닝용이었으므로 여기선 PK 만. 보존 = 테스트넷 리셋(약 월 1회, 물리 DELETE) 에 따른다.
-- ============================================
USE crypto_db;

-- 거래소가 보낸 원문 (append). 재구성·대조의 정답 원천. dedup_key 로 재수신(at-least-once) 흡수.
CREATE TABLE IF NOT EXISTS binance_user_events (
    event_id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_type    VARCHAR(32)  NOT NULL COMMENT 'executionReport | outboundAccountPosition | balanceUpdate | listStatus | ...',
    dedup_key     VARCHAR(64)  NOT NULL COMMENT 'executionReport: exec:<I> / 그 외: <type>:<E>',
    event_ms      BIGINT       NOT NULL COMMENT 'E (거래소 이벤트 시각)',
    symbol        VARCHAR(20)  NULL,
    order_id      BIGINT       NULL,
    exec_type     VARCHAR(24)  NULL COMMENT 'x: NEW|TRADE|CANCELED|REPLACED|REJECTED|EXPIRED|TRADE_PREVENTION',
    order_status  VARCHAR(24)  NULL COMMENT 'X',
    raw           JSON         NOT NULL,
    recv_ms       BIGINT       NOT NULL COMMENT '우리 수신 시각',
    created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id),
    UNIQUE KEY uq_dedup (dedup_key),
    KEY idx_order (order_id),
    KEY idx_event_ms (event_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 주문 = 변경되는 행 (거울 모드). 상태 전이가 있으므로 CDC 가 UPDATE·DELETE 를 실어 나른다.
CREATE TABLE IF NOT EXISTS virtual_orders (
    order_id        BIGINT        NOT NULL COMMENT 'Binance orderId (i)',
    symbol          VARCHAR(20)   NOT NULL,
    client_order_id VARCHAR(64)   NOT NULL COMMENT 'c — 우리가 만든 id: <strategy>-<yyyymmdd>-<n>',
    side            ENUM('BUY','SELL') NOT NULL,
    order_type      VARCHAR(20)   NOT NULL,
    time_in_force   VARCHAR(8)    NULL,
    price           DECIMAL(20,8) NOT NULL,
    orig_qty        DECIMAL(20,8) NOT NULL,
    executed_qty    DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT 'z 누적',
    cum_quote_qty   DECIMAL(24,8) NOT NULL DEFAULT 0 COMMENT 'Z 누적',
    status          VARCHAR(24)   NOT NULL COMMENT 'X: NEW|PARTIALLY_FILLED|FILLED|CANCELED|EXPIRED|REJECTED|EXPIRED_IN_MATCH',
    last_exec_type  VARCHAR(24)   NOT NULL COMMENT 'x 마지막',
    reject_reason   VARCHAR(32)   NULL,
    strategy        VARCHAR(32)   NOT NULL,
    fill_count      INT           NOT NULL DEFAULT 0,
    created_ms      BIGINT        NOT NULL COMMENT 'O 주문 생성 시각(거래소)',
    updated_ms      BIGINT        NOT NULL COMMENT 'E 마지막 반영 이벤트 시각',
    last_exec_id    BIGINT        NOT NULL COMMENT 'I 마지막 반영 이벤트',
    version         INT UNSIGNED  NOT NULL DEFAULT 1 COMMENT '우리 갱신 순번 — ClickHouse ReplacingMergeTree 버전',
    reset_epoch     INT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '테스트넷 리셋 세대(리셋마다 +1)',
    created_at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (order_id),
    UNIQUE KEY uq_client (client_order_id),
    KEY idx_symbol_created (symbol, created_ms),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 체결 = 불변 행 (보관소 모드). 1층 체결과 같은 성격.
CREATE TABLE IF NOT EXISTS virtual_fills (
    symbol           VARCHAR(20)   NOT NULL,
    fill_id          BIGINT        NOT NULL COMMENT 't Binance trade id (심볼 내 유일)',
    order_id         BIGINT        NOT NULL,
    side             ENUM('BUY','SELL') NOT NULL,
    price            DECIMAL(20,8) NOT NULL COMMENT 'L',
    qty              DECIMAL(20,8) NOT NULL COMMENT 'l',
    quote_qty        DECIMAL(24,8) NOT NULL COMMENT 'Y',
    commission       DECIMAL(20,8) NOT NULL COMMENT 'n',
    commission_asset VARCHAR(10)   NULL     COMMENT 'N',
    is_maker         TINYINT(1)    NOT NULL COMMENT 'm',
    filled_ms        BIGINT        NOT NULL COMMENT 'T',
    exec_id          BIGINT        NOT NULL COMMENT 'I',
    strategy         VARCHAR(32)   NOT NULL,
    created_at       TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (symbol, fill_id),
    KEY idx_order (order_id),
    KEY idx_filled (filled_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 잔고 스냅샷 (일 1회 + 리셋 감지). 대조용.
CREATE TABLE IF NOT EXISTS virtual_positions (
    as_of_day    DATE          NOT NULL,
    asset        VARCHAR(10)   NOT NULL,
    free         DECIMAL(24,8) NOT NULL,
    locked       DECIMAL(24,8) NOT NULL,
    snapshot_ms  BIGINT        NOT NULL,
    reset_epoch  INT UNSIGNED  NOT NULL DEFAULT 0,
    created_at   TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (as_of_day, asset)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
