-- =============================================================================
-- 小Ke商城 全量初始化脚本
-- 版本：v1.0   更新日期：2026-10-10
-- 数据库：MySQL 8.x   字符集：utf8mb4
-- 说明：本脚本可重复执行（DROP + CREATE），请勿在生产环境直接运行。
-- 表清单（7 张）：
--   t_user          用户表
--   t_address       收货地址表
--   t_category      商品分类表
--   t_product       商品表
--   t_product_attr  商品参数表
--   t_orders        订单主表
--   t_order_item    订单明细表
-- =============================================================================

CREATE DATABASE IF NOT EXISTS xiaoke_mall DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE xiaoke_mall;

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1. 用户表 t_user
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS t_user;
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- -----------------------------------------------------------------------------
-- 2. 收货地址表 t_address
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS t_address;
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收货地址表';

-- -----------------------------------------------------------------------------
-- 3. 商品分类表 t_category
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS t_category;
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类表';

-- -----------------------------------------------------------------------------
-- 4. 商品表 t_product
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS t_product;
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

-- -----------------------------------------------------------------------------
-- 5. 商品参数表 t_product_attr
--    用于存放规格参数（机身尺寸、电池容量、保修期…），支撑跨型号参数对比
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS t_product_attr;
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品参数表';

-- -----------------------------------------------------------------------------
-- 6. 订单主表 t_orders
--    status 枚举：0待付款 1已付款 2备货中 3已发货 4已签收 5已取消
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS t_orders;
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单主表';

-- -----------------------------------------------------------------------------
-- 7. 订单明细表 t_order_item
--    product_name / price / image 均为下单时快照，商品改名改价不影响历史订单
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS t_order_item;
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细表';

-- =============================================================================
-- 初始化数据（Seed Data）
-- =============================================================================

-- 分类：6 大一级品类（对标小米商城）
INSERT INTO t_category (id, name, parent_id, sort, status) VALUES
(1, '手机',       0, 1, 1),
(2, '电视影音',   0, 2, 1),
(3, '笔记本平板', 0, 3, 1),
(4, '智能家居',   0, 4, 1),
(5, '出行穿戴',   0, 5, 1),
(6, '生活周边',   0, 6, 1);

-- 示例商品（用于验证框架与接口）
INSERT INTO t_product (name, category_id, price, stock, sales, image, description, status) VALUES
('小Ke智能洗衣机 10KG', 4, 2999.00, 100, 0, NULL, '大容量滚筒洗衣机，支持智能投放与远程控制。', 1),
('小Ke手机 18 Pro',     1, 4999.00, 200, 0, NULL, '6.73 英寸 2K 屏，第三代骁龙 8 平台。', 1);

-- 示例商品参数
INSERT INTO t_product_attr (product_id, attr_name, attr_value, attr_group, sort) VALUES
(1, '洗涤容量', '10KG',     '基础参数', 1),
(1, '能效等级', '一级能效', '基础参数', 2),
(1, '电机类型', '直驱变频', '基础参数', 3),
(2, '屏幕尺寸', '6.73 英寸', '屏幕', 1),
(2, '分辨率',   '3200x1440', '屏幕', 2),
(2, '电池容量', '5000mAh',   '续航', 3);

-- 示例用户（password 为 BCrypt("123456") 的密文）
-- 实测（BCrypt cost 10，2026-10-10）：该密文与注释口令自洽，此 seed 账号可登录。
-- ⚠️ [CR-001] 该口令为弱口令（6 位连号递增数字），且本仓库为公开仓库。
--    该账号仅用于本地结构验证；部署前必须更换口令或删除。设计侧说明见 docs/12 第 9 节。
INSERT INTO t_user (username, password, nickname, phone, status) VALUES
('kevin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '小可', '13800000000', 1);
