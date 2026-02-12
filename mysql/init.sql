CREATE USER IF NOT EXISTS 'debezium'@'%' IDENTIFIED BY 'dbz_pass_2025';
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'debezium'@'%';
FLUSH PRIVILEGES;

CREATE TABLE IF NOT EXISTS orders (
    order_id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id        BIGINT NOT NULL,
    symbol         VARCHAR(20) NOT NULL COMMENT '종목코드',
    order_type     ENUM('BUY', 'SELL') NOT NULL,
    quantity       INT NOT NULL,
    price          DECIMAL(15, 2) NOT NULL,
    status         ENUM('PENDING', 'FILLED', 'CANCELLED', 'PARTIAL') NOT NULL DEFAULT 'PENDING',
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_user_id (user_id),
    INDEX idx_symbol (symbol),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_executions (
    execution_id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id       BIGINT NOT NULL,
    executed_qty   INT NOT NULL,
    executed_price DECIMAL(15, 2) NOT NULL,
    executed_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (order_id) REFERENCES orders(order_id),
    INDEX idx_order_id (order_id),
    INDEX idx_executed_at (executed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO orders (user_id, symbol, order_type, quantity, price, status) VALUES
(1001, '005930', 'BUY',  100, 71500.00, 'PENDING'),
(1002, '000660', 'SELL',  50, 178000.00, 'PENDING'),
(1003, '035420', 'BUY',   30, 215000.00, 'FILLED');
