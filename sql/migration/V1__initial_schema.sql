-- V1: seven existing business tables, without DROP, database selection or seed data.
-- Immutable after use. Future changes must add a new versioned migration.

CREATE TABLE t_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username    VARCHAR(50)  NOT NULL COMMENT '登录名',
    password    VARCHAR(100) NOT NULL COMMENT '密码（BCrypt 密文，禁止明文）',
    nickname    VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    phone       VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1正常 0禁用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    KEY idx_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';

CREATE TABLE t_address (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '地址ID',
    user_id        BIGINT       NOT NULL COMMENT '所属用户ID',
    receiver_name  VARCHAR(50)  NOT NULL COMMENT '收货人姓名',
    receiver_phone VARCHAR(20)  NOT NULL COMMENT '收货人电话',
    province       VARCHAR(50)  DEFAULT NULL COMMENT '省',
    city           VARCHAR(50)  DEFAULT NULL COMMENT '市',
    district       VARCHAR(50)  DEFAULT NULL COMMENT '区/县',
    detail_address VARCHAR(255) NOT NULL COMMENT '详细地址',
    is_default     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否默认：1是 0否',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='收货地址表';

CREATE TABLE t_category (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '分类ID',
    name        VARCHAR(50)  NOT NULL COMMENT '分类名称',
    parent_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '父分类ID，0 表示一级分类',
    icon        VARCHAR(255) DEFAULT NULL COMMENT '分类图标',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1启用 0停用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    KEY idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品分类表';

CREATE TABLE t_product (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    name        VARCHAR(128)  NOT NULL COMMENT '商品名称',
    category_id BIGINT        DEFAULT NULL COMMENT '分类ID',
    price       DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '售价',
    stock       INT           NOT NULL DEFAULT 0 COMMENT '库存',
    sales       INT           NOT NULL DEFAULT 0 COMMENT '下单件数：下单+、取消-（已创建且未取消）',
    -- [CR-005 · 2026-10-10] 列名保留 sales，但语义不是销量/累计销量；对外字段与 Java 字段统一为 orderCount（@TableField("sales")）
    image       VARCHAR(255)  DEFAULT NULL COMMENT '商品主图',
    description TEXT          DEFAULT NULL COMMENT '商品描述（可供 AI 引用）',
    status      TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：0下架 1上架',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    KEY idx_category_id (category_id),
    KEY idx_name (name),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品表';

CREATE TABLE t_product_attr (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '参数ID',
    product_id  BIGINT       NOT NULL COMMENT '所属商品ID',
    attr_name   VARCHAR(50)  NOT NULL COMMENT '参数名，如 电池容量',
    attr_value  VARCHAR(255) NOT NULL COMMENT '参数值，如 5000mAh',
    attr_group  VARCHAR(50)  DEFAULT NULL COMMENT '参数分组，如 屏幕/性能/续航',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    KEY idx_product_id (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品参数表';

CREATE TABLE t_orders (
    id                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '订单ID',
    order_no          VARCHAR(32)   NOT NULL COMMENT '业务订单号，对外暴露',
    user_id           BIGINT        NOT NULL COMMENT '下单用户ID',
    total_amount      DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '订单总额',
    status            TINYINT       NOT NULL DEFAULT 0 COMMENT '状态：0待付款 1已付款 2备货中 3已发货 4已签收 5已取消',
    logistics_company VARCHAR(50)   DEFAULT NULL COMMENT '物流公司',
    tracking_no       VARCHAR(64)   DEFAULT NULL COMMENT '运单号',
    receiver_name     VARCHAR(50)   NOT NULL COMMENT '收货人',
    receiver_phone    VARCHAR(20)   NOT NULL COMMENT '联系电话',
    receiver_address  VARCHAR(255)  NOT NULL COMMENT '收货地址（下单时快照）',
    remark            VARCHAR(255)  DEFAULT NULL COMMENT '订单备注',
    pay_time          DATETIME      DEFAULT NULL COMMENT '支付时间',
    ship_time         DATETIME      DEFAULT NULL COMMENT '发货时间',
    finish_time       DATETIME      DEFAULT NULL COMMENT '完成（签收）时间',
    create_time       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time       DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted           TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user_id (user_id),
    KEY idx_status (status),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单主表';

CREATE TABLE t_order_item (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '明细ID',
    order_id      BIGINT        NOT NULL COMMENT '关联 t_orders.id',
    order_no      VARCHAR(32)   NOT NULL COMMENT '订单号（冗余，便于查询）',
    product_id    BIGINT        NOT NULL COMMENT '商品ID',
    product_name  VARCHAR(128)  NOT NULL COMMENT '商品名称（快照）',
    product_image VARCHAR(255)  DEFAULT NULL COMMENT '商品图片（快照）',
    price         DECIMAL(10,2) NOT NULL COMMENT '下单时单价（快照）',
    quantity      INT           NOT NULL DEFAULT 1 COMMENT '购买数量',
    create_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    KEY idx_order_id (order_id),
    KEY idx_order_no (order_no),
    KEY idx_product_id (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单明细表';
