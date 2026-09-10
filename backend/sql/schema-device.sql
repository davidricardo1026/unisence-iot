-- =================================================================================
-- UNISENCE (统一互联) 平台设备管理模块数据库表结构 (MySQL DDL 脚本)
--
-- 建模原则：
--   1. 物模型是“类”，设备是“实例”——属性/事件/服务定义挂在 product 上，device 只承载实例元信息。
--   2. “模型定义”与“运行时数据”分离：本脚本只含【模型定义 + 设备元信息】(关系库)。
--      属性当前值与历史均在 GreptimeDB，均【不】落入本库任何表。
--   3. 复用只有一个概念——【标准产品】：product_type=2 的“模板态”产品行（不挂设备），
--      “创建”即复制其行 + tm_* 行，生成 product_type=1 的真产品；无独立模板表。
--   4. 分类只有一个机制——【标签】：品类已废弃，归类/检索靠 us_iot_tag（key-value，可筛选）。
--   5. 设备动态表单：产品 device_form_schema 定结构，设备 device_form_data 存值，
--      searchable 字段同步 us_iot_device_form_index。
--   6. 全部业务实体表（分类 A）六审计字段；关联/索引中间表（分类 B）仅创建审计、物理删除。
--
-- 表清单：
--   1. us_iot_product           产品（物模型归属 + 接入配置 + 设备动态表单 schema）
--   2. us_iot_tm_property       物模型-属性定义
--   3. us_iot_tm_event          物模型-事件定义
--   4. us_iot_tm_service        物模型-服务定义
--   5. us_iot_device            设备实例（含 device_form_data）
--   6. us_iot_tag               标签字典
--   7. us_iot_product_tag       产品-标签关联
--   8. us_iot_device_form_index 设备动态表单旁路检索（仅 searchable 非敏感字段）
--   9. us_iot_device_form_migration 表单 schema 百万设备版本化迁移任务
--
-- 运行时明细一律【不】落关系库：属性当前值/历史、事件、上下线 → GreptimeDB。
-- 物模型服务定义保存在本库；服务调用、回复落库与告警中心尚未交付。
-- =================================================================================

SET NAMES utf8mb4;
SET
FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- 1. 产品表 (us_iot_product)
--    承载物模型归属 + 接入配置元属性。物模型“动态属性”不在本表（见 us_iot_tm_*）。
--    标准产品 = product_type=2 的“模板态”行：本身不是可用产品（不挂设备/不入普通列表），
--    “创建”即复制其行 + tm_* 行，生成 product_type=1 的真产品。
--    产品的属性分层：静态规格 attributes / 设备动态表单 device_form_schema / 物模型动态属性 tm_*。
-- ----------------------------
CREATE TABLE `us_iot_product`
(
    `product_id`         bigint       NOT NULL AUTO_INCREMENT COMMENT '产品ID (主键)',
    `product_key`  char(6)      NOT NULL COMMENT '产品编码（固定6位小写短码；同编码允许一条标准产品与一条普通产品共存）',
    `product_name` varchar(100) NOT NULL COMMENT '产品名称（单行展示，最大100字符）',
    `node_type`          tinyint      NOT NULL DEFAULT 1 COMMENT '节点类型 (1-直连设备, 2-网关, 3-子设备)',
    `net_type`           tinyint               DEFAULT NULL COMMENT '入网方式 (1-WiFi, 2-蜂窝, 3-蓝牙, 4-有线)',
    `online_ttl_seconds` int NOT NULL DEFAULT 180 COMMENT '在线租约秒数 (超过该时长未收到任何上行消息即判离线；须≥驱动心跳周期的2~3倍；上报频率差异大故按产品配置)',
    `vendor`       varchar(100) DEFAULT NULL COMMENT '厂商名称（最大100字符）',
    `model`        varchar(100) DEFAULT NULL COMMENT '产品型号（最大100字符）',
    `icon`         varchar(50)  DEFAULT NULL COMMENT '产品图标组件名（最大50字符）',
    `icon_url`     varchar(255) DEFAULT NULL COMMENT '产品图片稳定访问地址（最大255字符）',
    `description`        text COMMENT '产品描述',
    `attributes`         json COMMENT '产品级静态规格 (保修/功率等；仅展示不筛；与设备动态表单无关)',
    `device_form_schema` json COMMENT '设备实例动态表单定义 (产品定结构；建设备时按此填值；含 groups/fields/required/searchable/sensitive/mask；NULL=无额外表单)',
    `device_form_version` int NOT NULL DEFAULT 0 COMMENT '已发布设备表单schema/index代际 (候选迁移完成并原子发布时+1；百万设备L1/L2惰性失效，禁止状态更新修改)',
    `product_type`       tinyint      NOT NULL DEFAULT 1 COMMENT '产品类型 (1-普通产品, 2-标准产品/模板；模板态不挂设备、不入普通列表，"创建"=复制成 type=1 真产品)',
    `create_by`          bigint                DEFAULT NULL COMMENT '创建者用户ID',
    `create_time`        datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`          bigint                DEFAULT NULL COMMENT '更新者用户ID',
    `update_time`        datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`            bigint       NOT NULL DEFAULT 0 COMMENT '逻辑删除 (0-未删除, 非0=已删除，值为主键ID)',
    `version`            int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`product_id`),
    UNIQUE KEY `uk_product_key_type_deleted` (`product_key`, `product_type`, `deleted`),
    KEY `idx_prod_type` (`product_type`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci COMMENT='产品/物模型归属表';

-- ----------------------------
-- 2. 物模型-属性定义表 (us_iot_tm_property)
--    属性 = 有“当前值”的状态，可双向读写。
--    值落点（与 MySQL 无关）：按 data_type + retention_days 路由到 12 张固定属性时序表。
--    2026-08-02：store_latest / store_history 两列已删除 —— 声明的属性一律存储，
--    存储成本由时序数据库 TTL（默认 180 天）承担。当前值由 GreptimeDB 派生，不另建 Redis 影子。
-- ----------------------------
CREATE TABLE `us_iot_tm_property`
(
    `property_id`         bigint       NOT NULL AUTO_INCREMENT COMMENT '属性定义ID (主键)',
    `product_id`          bigint       NOT NULL COMMENT '所属产品ID (对应us_iot_product)',
    `identifier`    varchar(50)  NOT NULL COMMENT '标识符（产品内唯一，最大50字符）',
    `property_name` varchar(100) NOT NULL COMMENT '属性展示名（最大100字符）',
    `data_type`     varchar(10)  NOT NULL COMMENT '数据类型代码（最大10字符）',
    `access_mode`         tinyint      NOT NULL DEFAULT 1 COMMENT '读写权限 (1-r 只读, 2-rw 读写)',
    `unit`          varchar(20) DEFAULT NULL COMMENT '单位符号（最大20字符）',
    `retention_days` smallint NOT NULL DEFAULT 180 COMMENT '属性历史保留档位（90/180/360天；创建后不可修改）',
    `create_by`           bigint                DEFAULT NULL COMMENT '创建者用户ID',
    `create_time`       datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`         bigint                DEFAULT NULL COMMENT '更新者用户ID',
    `update_time`       datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           bigint       NOT NULL DEFAULT 0 COMMENT '逻辑删除 (0-未删除, 非0=已删除，值为主键ID)',
    `version`           int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`property_id`),
    UNIQUE KEY `uk_prop_product_identifier_deleted` (`product_id`, `identifier`, `deleted`),
    KEY                 `idx_prop_product_id` (`product_id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci COMMENT='物模型属性定义表';

-- ----------------------------
-- 3. 物模型-事件定义表 (us_iot_tm_event)
--    事件 = 瞬时“发生”，单向上报。本表仅存【定义/schema】；
--    事件【实例】走动态时序表 evt_{product_key}_{identifier}，不落 MySQL。
--    事件上报报文逐 identifier 按 input_params 校验。
-- ----------------------------
CREATE TABLE `us_iot_tm_event`
(
    `event_id`      bigint       NOT NULL AUTO_INCREMENT COMMENT '事件定义ID (主键)',
    `product_id`    bigint       NOT NULL COMMENT '所属产品ID (对应us_iot_product)',
    `identifier`     varchar(50) NOT NULL COMMENT '标识符（产品内唯一，入库小写，最大50字符）',
    `event_name` varchar(100) NOT NULL COMMENT '事件展示名（最大100字符）',
    `event_type`    tinyint      NOT NULL DEFAULT 1 COMMENT '事件等级 (1-info, 2-warning, 3-error)',
    `input_params`   json COMMENT '输入参数定义（事件无输出；JSON 数组，元素含 identifier+name+dataType，全部为 FIELD）',
    `ttl_enabled` tinyint NOT NULL COMMENT '是否使用事件表级 TTL（0-继承数据库默认值，1-自定义）',
    `ttl_value`   int     DEFAULT NULL COMMENT '自定义事件 TTL 正整数数值；使用默认值时为空',
    `ttl_unit`    char(1) DEFAULT NULL COMMENT '自定义事件 TTL 单位（h-小时，d-天）；使用默认值时为空',
    `create_by`     bigint                DEFAULT NULL COMMENT '创建者用户ID',
    `create_time`   datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`     bigint                DEFAULT NULL COMMENT '更新者用户ID',
    `update_time`   datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       bigint       NOT NULL DEFAULT 0 COMMENT '逻辑删除 (0-未删除, 非0=已删除，值为主键ID)',
    `version`       int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`event_id`),
    UNIQUE KEY `uk_event_product_identifier_deleted` (`product_id`, `identifier`, `deleted`),
    KEY             `idx_event_product_id` (`product_id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci COMMENT='物模型事件定义表';

-- ----------------------------
-- 4. 物模型-服务定义表 (us_iot_tm_service)
--    服务 = 可被调用的方法，带入参/出参。
-- ----------------------------
CREATE TABLE `us_iot_tm_service`
(
    `service_id`    bigint       NOT NULL AUTO_INCREMENT COMMENT '服务定义ID (主键)',
    `product_id`    bigint       NOT NULL COMMENT '所属产品ID (对应us_iot_product)',
    `identifier`   varchar(50)  NOT NULL COMMENT '标识符（产品内唯一，最大50字符）',
    `service_name` varchar(100) NOT NULL COMMENT '服务展示名（最大100字符）',
    `input_params`  json COMMENT '入参定义 (JSON 数组，元素含 identifier+name+dataType)',
    `output_params` json COMMENT '出参定义 (JSON 数组，元素含 identifier+name+dataType)',
    `call_type`     tinyint      NOT NULL DEFAULT 1 COMMENT '调用类型 (1-同步, 2-异步)',
    `create_by`     bigint                DEFAULT NULL COMMENT '创建者用户ID',
    `create_time`   datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`     bigint                DEFAULT NULL COMMENT '更新者用户ID',
    `update_time`   datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       bigint       NOT NULL DEFAULT 0 COMMENT '逻辑删除 (0-未删除, 非0=已删除，值为主键ID)',
    `version`       int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`service_id`),
    UNIQUE KEY `uk_svc_product_identifier_deleted` (`product_id`, `identifier`, `deleted`),
    KEY             `idx_svc_product_id` (`product_id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci COMMENT='物模型服务定义表';

-- ----------------------------
-- 5. 设备实例表 (us_iot_device)
--    只放低频变化的元信息与状态；属性当前值与历史均在 GreptimeDB，不进本表。
--    device_code = 产品内设备业务码 (不可变，对齐 Kafka DeviceID / README device_code)；device_name 仅可变展示名。
--    gateway_id 自关联：直连/网关为 NULL，子设备指向所属网关；node_type 默认拷贝产品，允许覆盖（决议 A）。
--    约束：product_id 只能指向普通产品（product_type=1），不可挂到标准产品/模板上（业务层校验）。
--    device_form_data = 对齐产品 device_form_schema 的实例值（敏感字段以 enc:v*: 信封存储）。
--    address/经纬度与表单 install 可并存（决议 B）；无 dept_id（决议 E）。
--    受管分类见 us_iot_product_tag；可搜自定义字段见 form_index。
-- ----------------------------
CREATE TABLE `us_iot_device`
(
    `device_id`        bigint      NOT NULL AUTO_INCREMENT COMMENT '设备ID (主键)',
    `product_id`       bigint      NOT NULL COMMENT '所属产品ID (对应us_iot_product，决定用哪套物模型与动态表单)',
    `device_code`      varchar(50) NOT NULL COMMENT '设备业务码（产品内唯一、最大50字符）',
    `device_name`      varchar(100) DEFAULT NULL COMMENT '设备展示名/昵称（最大100字符）',
    `gateway_id`       bigint               DEFAULT NULL COMMENT '所属网关设备ID (自关联us_iot_device；直连/网关为NULL)',
    `node_type`        tinyint     NOT NULL DEFAULT 1 COMMENT '节点类型 (1-直连, 2-网关, 3-子设备；建设备默认拷贝产品node_type，允许覆盖；子设备须填gateway_id)',
    `status`           tinyint NOT NULL DEFAULT 0 COMMENT '设备状态 (0-未激活, 1-在线, 2-离线, 3-未知/驱动失联；事件驱动，仅跳变时更新，勿每报UPDATE；实时活跃查 Redis 租约)',
    `status_event_ms`  bigint NOT NULL DEFAULT 0 COMMENT '最近已落库在线状态transition的Redis Stream毫秒ID；与status_event_seq组成单调fence，防重试乱序覆盖',
    `status_event_seq` bigint NOT NULL DEFAULT 0 COMMENT '最近已落库在线状态transition的Redis Stream序号；同毫秒内比较顺序',
    `last_online_at`   datetime             DEFAULT NULL COMMENT '最近一次上线跳变时刻 (事件驱动；非最近上报时刻——那属高频，实时活跃走Redis)',
    `activated_at`     datetime             DEFAULT NULL COMMENT '首次激活时间',
    `longitude`        decimal(10, 7)       DEFAULT NULL COMMENT '经度 (固定设备位置；可与表单install字段并存)',
    `latitude`         decimal(10, 7)       DEFAULT NULL COMMENT '纬度 (固定设备位置；可与表单install字段并存)',
    `address`          varchar(255) DEFAULT NULL COMMENT '平台侧安装/解析地址（最大255字符）',
    `device_form_data` json COMMENT '设备实例表单值 (对齐所属产品 device_form_schema；扁平 key；sensitive 存 enc:v*: 信封；不作列表主筛选—可搜键走 form_index)',
    `create_by`        bigint               DEFAULT NULL COMMENT '创建者用户ID',
    `create_time`      datetime             DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`        bigint               DEFAULT NULL COMMENT '更新者用户ID',
    `update_time`      datetime             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          bigint      NOT NULL DEFAULT 0 COMMENT '逻辑删除 (0-未删除, 非0=已删除，值为主键ID)',
    `version`          int         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`device_id`),
    UNIQUE KEY `uk_dev_product_code_deleted` (`product_id`, `device_code`, `deleted`),
    KEY                `idx_dev_product_id` (`product_id`),
    KEY                `idx_dev_gateway_id` (`gateway_id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci COMMENT='设备实例表';

-- ----------------------------
-- 6. 标签字典表 (us_iot_tag)
--    key-value 标签，替代已废弃的“品类”承担归类/检索。tag_key 是不可变且全局唯一的业务键。
-- ----------------------------
CREATE TABLE `us_iot_tag`
(
    `tag_id`      bigint       NOT NULL AUTO_INCREMENT COMMENT '标签ID (主键)',
    `tag_key`     varchar(50)  NOT NULL COMMENT '不可变标签键（最大50字符）',
    `tag_value`   varchar(100) NOT NULL COMMENT '标签值（最大100字符）',
    `color`       char(7)      DEFAULT NULL COMMENT '展示色（固定 #RRGGBB）',
    `description` varchar(255) DEFAULT NULL COMMENT '标签说明（最大255字符）',
    `create_by`   bigint                DEFAULT NULL COMMENT '创建者用户ID',
    `create_time` datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`   bigint                DEFAULT NULL COMMENT '更新者用户ID',
    `update_time` datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     bigint       NOT NULL DEFAULT 0 COMMENT '逻辑删除 (0-未删除, 非0=已删除，值为主键ID)',
    `version`     int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`tag_id`),
    UNIQUE KEY `uk_tag_key_deleted` (`tag_key`, `deleted`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci COMMENT='标签字典表';

-- ----------------------------
-- 7. 产品-标签关联表 (us_iot_product_tag)
--    产品↔标签 多对多。中间表（分类 B）：独立主键 + 仅创建审计（create_*），物理删除。
--    打标签=INSERT、去标签=真 DELETE（无就地修改、无软删）；UNIQUE(product_id, tag_id) 干净不拖 deleted。
--    按标签筛选产品：SELECT product_id ... WHERE tag_id IN (...)。
-- ----------------------------
CREATE TABLE `us_iot_product_tag`
(
    `id`          bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `product_id`  bigint NOT NULL COMMENT '产品ID (对应us_iot_product)',
    `tag_id`      bigint NOT NULL COMMENT '标签ID (对应us_iot_tag)',
    `create_by`   bigint   DEFAULT NULL COMMENT '创建者用户ID',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pt_product_tag` (`product_id`, `tag_id`),
    KEY           `idx_pt_product_id` (`product_id`),
    KEY           `idx_pt_tag_id` (`tag_id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci COMMENT='产品标签关联表 (B类·仅创建审计·物理删除：去标签=真删)';

-- ----------------------------
-- 8. 设备动态表单旁路索引表 (us_iot_device_form_index)
--    仅同步 schema 中 searchable=true 且 sensitive=false 的字段，供设备管理列表按产品筛选。
--    值按字段类型投影：文本/枚举、数值、布尔值分列存储，数值范围比较无需 CAST，命中专用索引。
--    中间表（分类 B）：独立主键 + 仅创建审计；同一设备允许 active/target 两个 schema_version 短暂并存。
--    敏感值禁止入本表。
-- ----------------------------
CREATE TABLE `us_iot_device_form_index`
(
    `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `device_id`   bigint       NOT NULL COMMENT '设备ID (对应us_iot_device)',
    `product_id`  bigint       NOT NULL COMMENT '产品ID (冗余，列表按产品筛)',
    `schema_version` int NOT NULL COMMENT '生成本行的产品device_form_version；查询只读当前active版本',
    `field_key`     varchar(50) NOT NULL COMMENT '表单字段键（最大50字符）',
    `value_text`     varchar(255) DEFAULT NULL COMMENT 'string/enum可搜索规范化值（最大255字符；text禁止searchable）',
    `value_decimal` decimal(30, 10) DEFAULT NULL COMMENT 'int/float 规范化数值（最多20位整数、10位小数）',
    `value_boolean` tinyint         DEFAULT NULL COMMENT 'bool 规范化值（0=false, 1=true）',
    `create_by`     bigint          DEFAULT NULL COMMENT '创建者用户ID',
    `create_time`   datetime        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dfi_device_ver_field` (`device_id`, `schema_version`, `field_key`),
    KEY              `idx_dfi_prod_ver_key_text` (`product_id`, `schema_version`, `field_key`, `value_text`),
    KEY              `idx_dfi_prod_ver_key_decimal` (`product_id`, `schema_version`, `field_key`, `value_decimal`),
    KEY              `idx_dfi_prod_ver_key_boolean` (`product_id`, `schema_version`, `field_key`, `value_boolean`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci COMMENT='设备动态表单版本化旁路检索表 (B类·仅创建审计·物理删除；仅可搜非敏感字段)';

-- ----------------------------
-- 9. 设备动态表单迁移任务表 (us_iot_device_form_migration)
--    分类 A：会推进状态、游标和统计；候选 schema 保存在任务中，active schema 在任务完成前保持不变。
--    同一产品只允许一个未终态任务，由 Service 在锁定产品行后校验；不写 CHECK / FOREIGN KEY。
-- ----------------------------
CREATE TABLE `us_iot_device_form_migration`
(
    `migration_id`         bigint  NOT NULL AUTO_INCREMENT COMMENT '迁移任务ID',
    `product_id`           bigint  NOT NULL COMMENT '产品ID',
    `from_version`         int     NOT NULL COMMENT '提交任务时的active device_form_version',
    `target_version`       int     NOT NULL COMMENT '候选版本；固定为from_version+1',
    `target_schema`        json COMMENT '候选device_form_schema；NULL=移除表单；完成前不得覆盖产品active schema',
    `state`                tinyint NOT NULL DEFAULT 0 COMMENT '任务状态 (0-待处理, 1-构建中, 2-可发布, 3-已发布, 4-失败, 5-已取消)',
    `scan_upper_device_id` bigint  NOT NULL DEFAULT 0 COMMENT '任务创建时产品设备ID上界；其后新增/编辑由保存事务双写target版本',
    `cursor_device_id`     bigint  NOT NULL DEFAULT 0 COMMENT '已完成的最大设备ID；按主键游标分页，禁止OFFSET',
    `processed_count`      bigint  NOT NULL DEFAULT 0 COMMENT '已构建target索引的设备数',
    `failure_reason`       text COMMENT '失败原因；成功时为NULL',
    `create_by`            bigint           DEFAULT NULL COMMENT '创建者用户ID',
    `create_time`          datetime         DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`            bigint           DEFAULT NULL COMMENT '更新者用户ID',
    `update_time`          datetime         DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`              bigint  NOT NULL DEFAULT 0 COMMENT '逻辑删除 (0-未删除, 非0=已删除，值为主键ID)',
    `version`              int     NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`migration_id`),
    KEY                    `idx_dfm_product_target` (`product_id`, `target_version`, `deleted`),
    KEY                    `idx_dfm_product_state` (`product_id`, `state`, `deleted`),
    KEY                    `idx_dfm_state_update` (`state`, `update_time`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci COMMENT='设备动态表单schema版本化迁移任务 (A类)';
