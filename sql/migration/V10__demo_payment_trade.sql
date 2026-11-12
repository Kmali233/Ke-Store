CREATE TABLE t_demo_payment_trade (
    payment_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (payment_no),
    CONSTRAINT fk_demo_trade_payment FOREIGN KEY (payment_no) REFERENCES t_payment_order(payment_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='演示收银台的已付款回执';
