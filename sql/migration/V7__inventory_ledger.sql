-- Maintenance window required: stop every old writer before V7 and keep stopped through SKU backfill.
CREATE TABLE t_inventory_adjustment (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 business_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 sku_id BIGINT NOT NULL,
 event_type VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 quantity INT NOT NULL,
 reason VARCHAR(200) NOT NULL,
 actor_id BIGINT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_adjustment_business(business_no),
 KEY idx_adjustment_sku(sku_id,id),
 CONSTRAINT ck_adjustment_type CHECK(event_type IN ('INBOUND','ADJUSTMENT')),
 CONSTRAINT ck_adjustment_quantity CHECK(quantity<>0 AND (event_type<>'INBOUND' OR quantity>0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE t_inventory_ledger (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 sku_id BIGINT NOT NULL,
 event_type VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 business_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 quantity INT NOT NULL,
 sales_quantity INT NOT NULL DEFAULT 0,
 before_stock INT NOT NULL,
 after_stock INT NOT NULL,
 actor_id BIGINT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 restore_order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
   GENERATED ALWAYS AS (CASE WHEN event_type IN ('CANCEL_RESTORE','REFUND_RESTORE') THEN business_no ELSE NULL END) STORED,
 UNIQUE KEY uk_inventory_event(sku_id,event_type,business_no),
 UNIQUE KEY uk_inventory_restore(sku_id,restore_order_no),
 KEY idx_inventory_business(business_no,id),
 CONSTRAINT ck_inventory_type CHECK(event_type IN ('OPENING','INBOUND','ADJUSTMENT','ORDER_OCCUPY','CANCEL_RESTORE','REFUND_RESTORE')),
 CONSTRAINT ck_inventory_balance CHECK(before_stock>=0 AND after_stock>=0 AND CAST(before_stock AS SIGNED)+quantity=after_stock),
 CONSTRAINT ck_inventory_direction CHECK(
   (event_type='OPENING' AND before_stock=0 AND quantity>=0 AND sales_quantity>=0) OR
   (event_type='INBOUND' AND quantity>0 AND sales_quantity=0) OR
   (event_type='ADJUSTMENT' AND quantity<>0 AND sales_quantity=0) OR
   (event_type='ORDER_OCCUPY' AND quantity<0 AND sales_quantity=-quantity) OR
   (event_type IN ('CANCEL_RESTORE','REFUND_RESTORE') AND quantity>0 AND sales_quantity=-quantity))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO t_inventory_ledger(sku_id,event_type,business_no,quantity,sales_quantity,before_stock,after_stock)
 SELECT id,'OPENING','OPENING',stock,sales,0,stock FROM t_product_sku ORDER BY id;
