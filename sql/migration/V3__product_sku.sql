-- Stop all old writers before applying. Finish batched backfill via scripts/db_migrate.py.
-- t_product price/stock/sales are retained as frozen pre-cutover values, never transaction authority.
CREATE TABLE t_product_sku (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    spec_json VARCHAR(1024) NOT NULL,
    spec_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    stock INT NOT NULL DEFAULT 0,
    sales INT NOT NULL DEFAULT 0,
    image VARCHAR(255) DEFAULT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    deleted TINYINT NOT NULL DEFAULT 0,
    is_default TINYINT NOT NULL DEFAULT 0,
    default_product_id BIGINT GENERATED ALWAYS AS (CASE WHEN is_default=1 THEN product_id ELSE NULL END) STORED,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY(id),
    UNIQUE KEY uk_sku_specs(product_id,spec_hash),
    UNIQUE KEY uk_default_sku(default_product_id),
    KEY idx_sku_product(product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SKU唯一交易库存权威';

ALTER TABLE t_order_item
    ADD COLUMN sku_id BIGINT DEFAULT NULL,
    ADD COLUMN sku_name VARCHAR(128) DEFAULT NULL,
    ADD COLUMN sku_spec VARCHAR(1024) DEFAULT NULL,
    ADD KEY idx_item_sku(sku_id);

CREATE TABLE t_catalog_migration (
    version SMALLINT NOT NULL,
    completed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分批默认SKU回填完成标记';
