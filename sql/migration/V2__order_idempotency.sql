-- Successful creation receipts are retained indefinitely. Never reuse or delete a key.
CREATE TABLE t_order_idempotency (
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    hash_version SMALLINT NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_no VARCHAR(32) NOT NULL,
    result_status INT NOT NULL,
    total_amount DECIMAL(10,2) NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, idempotency_key),
    UNIQUE KEY uk_idempotency_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='下单成功幂等回执';
