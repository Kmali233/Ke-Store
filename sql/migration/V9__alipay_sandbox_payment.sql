CREATE TABLE t_payment_order (
    order_id BIGINT NOT NULL,
    payment_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    channel VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    app_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    seller_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    total_amount DECIMAL(10,2) NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    trade_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    pay_time DATETIME DEFAULT NULL,
    PRIMARY KEY (order_id),
    UNIQUE KEY uk_payment_no (payment_no),
    UNIQUE KEY uk_payment_trade (channel, trade_no),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES t_orders(id),
    CONSTRAINT chk_payment_amount CHECK (total_amount > 0),
    CONSTRAINT chk_payment_state CHECK (
        (status = 'PENDING' AND trade_no IS NULL AND pay_time IS NULL)
        OR (status = 'SUCCEEDED' AND trade_no IS NOT NULL AND pay_time IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单支付单';
