CREATE TABLE t_order_refund (
    order_id BIGINT NOT NULL PRIMARY KEY,
    refund_no CHAR(32) NOT NULL UNIQUE,
    amount DECIMAL(10,2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT ck_order_refund_amount CHECK (amount > 0),
    CONSTRAINT ck_order_refund_status CHECK (status IN ('REQUESTED','PROCESSING','REJECTED','SUCCEEDED','FAILED')),
    INDEX idx_refund_status (status, order_id)
);

CREATE TABLE t_demo_refund_trade (
    payment_no VARCHAR(32) NOT NULL PRIMARY KEY,
    refund_no CHAR(32) NOT NULL UNIQUE,
    amount DECIMAL(10,2) NOT NULL
);
