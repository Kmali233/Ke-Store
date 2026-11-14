-- Existing orders keep NULL deadlines and are excluded from automatic closure.
ALTER TABLE t_orders
    ADD COLUMN payment_expires_at DATETIME NULL COMMENT 'Payment deadline in UTC',
    ADD COLUMN close_reason VARCHAR(16) NULL COMMENT 'First close trigger',
    ADD COLUMN close_token CHAR(36) NULL COMMENT 'Current worker lease token',
    ADD COLUMN close_attempts INT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN close_next_attempt_at DATETIME NULL COMMENT 'UTC scan deadline or lease end',
    ADD COLUMN close_finished_at DATETIME NULL COMMENT 'UTC close resolution time',
    ADD INDEX idx_orders_close_scan (status, deleted, close_next_attempt_at, id);

ALTER TABLE t_payment_order
    DROP CHECK chk_payment_state,
    ADD CONSTRAINT chk_payment_state CHECK (
        (status IN ('PENDING','CLOSED') AND trade_no IS NULL AND pay_time IS NULL)
        OR (status = 'SUCCEEDED' AND trade_no IS NOT NULL AND pay_time IS NOT NULL)
    );
