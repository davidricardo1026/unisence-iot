-- =================================================================================
-- UNISENCE (统一互联) 平台规则引擎模块数据库表结构 (MySQL DDL 脚本)
--
-- 建模原则：
--   1. 规则按「有无窗口状态」分成两张表：即时规则零状态，窗口规则每 (规则,设备,窗口) 一份累加器。
--      分表而不是加判别字段，是因为窗口与聚合两列只对一类有意义 ——
--      合表意味着一半行恒为 NULL，且「有几条窗口规则」这个直接等于容量的数字要靠过滤条件才能数出来。
--   2. 两张规则表都是 A 类业务实体：六审计字段、逻辑删除、version 乐观锁。
--   3. revision 是运行时规则修订号，用于缓存键、窗口状态与档位状态隔离，不替代 version 乐观锁。
--   4. 档位表两类规则共用，用 rule_kind 区分归属：档位字段在两类规则下完全相同，
--      不存在「一半行为 NULL」的问题。分表的判据是「有没有仅适用于一类的列」，不是概念归属。
--   5. 绑定表按类别分两张：它会被候选索引构建高频扫描，两张窄表比一张宽表加过滤条件更直接。
--   6. 关联表是 B 类：仅创建审计、物理删除、无 update/deleted/version。
--   7. 产品引用对应 schema-device.sql 的 us_iot_product；仅允许绑定 product_type=1 的普通产品，由业务事务校验。
--   8. 规则配置同步复用 us_sys_metadata_head/us_sys_metadata_change 提交水位总线；
--      本脚本不保存运行时窗口、档位或消息去重数据。
--   9. 枚举取值、数值区间与 JSON 结构一律由 Java 枚举 + 应用层校验保证，DDL 不写 CHECK / FOREIGN KEY 约束。
--
-- 注意：两张规则表各有独立的 AUTO_INCREMENT，rule_id 会跨表重复。
--      运行时的全局身份是 RuleKind + rule_id（即 I:12 / W:12），
--      下游幂等键为 msgId + ruleKind + ruleId + revision，四者缺一不可。
--
-- 表清单：
--   1. us_iot_rule_instant          即时规则定义（无窗口、无累加器）
--   2. us_iot_rule_window           窗口规则定义（有累加器状态）
--   3. us_iot_rule_level            规则档位（两类共用）
--   4. us_iot_rule_instant_product  即时规则-普通产品多对多绑定
--   5. us_iot_rule_window_product   窗口规则-普通产品多对多绑定
--   6. us_iot_rule_kafka_output     Kafka 输出定义（同集群精确 Topic 登记）
--   7. us_iot_rule_level_kafka_output 档位-Kafka 输出绑定
--   8. us_iot_rule_route            透传路由规则（无脚本、无档位、无状态）
--   9. us_iot_rule_route_product    透传路由-普通产品多对多绑定
--  10. us_iot_rule_route_kafka_output 透传路由-Kafka 输出绑定
-- =================================================================================

SET NAMES utf8mb4;
SET
FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- 1. 即时规则定义表 (us_iot_rule_instant)
--    逐条消息判档，运行期零窗口状态；不参与 msgId 去重。
--    保存前必须完成配置校验、Groovy 编译、静态检查和产品物模型校验。
-- ----------------------------
CREATE TABLE `us_iot_rule_instant`
(
    `rule_id`         bigint                                         NOT NULL AUTO_INCREMENT COMMENT '规则ID (主键；与 us_iot_rule_window 不共享ID空间)',
    `rule_code`       varchar(60)                                    NOT NULL COMMENT '规则业务编码（创建后不可变；业务最大50字符，含约20%物理余量）',
    `rule_name`       varchar(144)                                   NOT NULL COMMENT '规则名称（业务最大120字符，含约20%物理余量）',
    `message_type`    varchar(10)                                    NOT NULL COMMENT '规则模型类型 (property/event)',
    `listener_config` json                                                    DEFAULT NULL COMMENT '监听配置对象（identifiers 白名单；产品绑定不存此列）',
    `value_config`    json                                                    DEFAULT NULL COMMENT '被监控信号取值配置（valueIdentifier/valueSource）；全部档位均为 SCRIPT 条件时可空',
    `emit_mode` varchar(20) NOT NULL DEFAULT 'LEVEL_TRANSITION' COMMENT '输出模式 (LEVEL_TRANSITION/EVERY_MATCH)；最长枚举16字符，含约20%物理余量',
    `filter_script`   mediumtext                                     NOT NULL COMMENT '规范化前置过滤脚本（纯闸门，不承担告警条件）；空白请求保存为 return true',
    `script_sha256`   char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'filter 与各档位脚本按 severity 升序规范化拼接后的 SHA-256 十六进制值',
    `compile_result`  json                                           NOT NULL COMMENT '最近一次成功保存的编译信息与静态检查摘要对象',
    `error_policy`    varchar(15)                                    NOT NULL DEFAULT 'DLQ_MESSAGE' COMMENT '错误策略 (DLQ_MESSAGE/SKIP_RULE/DROP_MESSAGE)',
    `status`          tinyint                                        NOT NULL DEFAULT 0 COMMENT '规则状态 (0-停用, 1-启用；新建默认停用)',
    `revision`        bigint                                         NOT NULL DEFAULT 1 COMMENT '运行时规则修订号（每次成功修改递增；用于缓存键与档位状态隔离，不代表发版）',
    `create_by`       bigint                                                  DEFAULT NULL COMMENT '创建者用户ID',
    `create_time`     datetime                                                DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`       bigint                                                  DEFAULT NULL COMMENT '更新者用户ID',
    `update_time`     datetime                                                DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         bigint                                         NOT NULL DEFAULT 0 COMMENT '逻辑删除 (0-未删除, 非0=已删除，值为主键ID)',
    `version`         int                                            NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`rule_id`),
    UNIQUE KEY `uk_rule_instant_code_deleted` (`rule_code`, `deleted`),
    KEY               `idx_rule_instant_enabled_type` (`deleted`, `status`, `message_type`, `rule_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='即时规则定义表（无窗口·零状态）';

-- ----------------------------
-- 2. 窗口规则定义表 (us_iot_rule_window)
--    消息累加 + 窗口到期结算判档。每 (规则,设备,窗口) 一份累加器 ——
--    本表行数直接决定 State Store 体积、changelog 吞吐、PVC 规格与故障恢复时间。
--    公共列与 us_iot_rule_instant 完全一致，多出 window_config / aggregate_config 两列。
-- ----------------------------
CREATE TABLE `us_iot_rule_window`
(
    `rule_id`          bigint                                         NOT NULL AUTO_INCREMENT COMMENT '规则ID (主键；与 us_iot_rule_instant 不共享ID空间)',
    `rule_code`        varchar(60)                                    NOT NULL COMMENT '规则业务编码（创建后不可变；业务最大50字符，含约20%物理余量）',
    `rule_name`        varchar(144)                                   NOT NULL COMMENT '规则名称（业务最大120字符，含约20%物理余量）',
    `message_type`     varchar(10)                                    NOT NULL COMMENT '规则模型类型 (property/event)',
    `listener_config`  json                                                    DEFAULT NULL COMMENT '监听配置对象（identifiers 白名单；产品绑定不存此列）',
    `window_config`    json                                           NOT NULL COMMENT '窗口配置对象（type/timeMode/sizeMillis/advanceMillis/graceMillis/retentionMillis/stateScope）',
    `aggregate_config` json                                           NOT NULL COMMENT '聚合配置对象（type/valueIdentifier/valueSource）',
    `filter_script`    mediumtext                                     NOT NULL COMMENT '规范化前置过滤脚本（纯闸门，不承担告警条件）；空白请求保存为 return true',
    `script_sha256`    char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'filter 与各档位脚本按 severity 升序规范化拼接后的 SHA-256 十六进制值',
    `compile_result`   json                                           NOT NULL COMMENT '最近一次成功保存的编译信息与静态检查摘要对象',
    `error_policy`     varchar(15)                                    NOT NULL DEFAULT 'DLQ_MESSAGE' COMMENT '错误策略 (DLQ_MESSAGE/SKIP_RULE/DROP_MESSAGE)',
    `status`           tinyint                                        NOT NULL DEFAULT 0 COMMENT '规则状态 (0-停用, 1-启用；新建默认停用)',
    `revision`         bigint                                         NOT NULL DEFAULT 1 COMMENT '运行时规则修订号（每次成功修改递增；用于缓存键、窗口状态与档位状态隔离，不代表发版）',
    `create_by`        bigint                                                  DEFAULT NULL COMMENT '创建者用户ID',
    `create_time`      datetime                                                DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`        bigint                                                  DEFAULT NULL COMMENT '更新者用户ID',
    `update_time`      datetime                                                DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          bigint                                         NOT NULL DEFAULT 0 COMMENT '逻辑删除 (0-未删除, 非0=已删除，值为主键ID)',
    `version`          int                                            NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`rule_id`),
    UNIQUE KEY `uk_rule_window_code_deleted` (`rule_code`, `deleted`),
    KEY                `idx_rule_window_enabled_type` (`deleted`, `status`, `message_type`, `rule_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='窗口规则定义表（有累加器状态）';

-- ----------------------------
-- 3. 规则档位表 (us_iot_rule_level)
--    规则决定「算什么」，档位决定「什么时候报」。同一 (规则,设备) 任一时刻恰好处于一个档位，
--    输出只在档位发生变化时产生 —— 这一条同时消掉了告警风暴、terminal 与恢复策略三个旧机制。
--
--    档位不做逻辑删除：它随规则整体保存（一个保存聚合），删档位就是从该规则的档位集合里移除，
--    没有独立生命周期，因此也没有 deleted/version/update_by。
-- ----------------------------
CREATE TABLE `us_iot_rule_level`
(
    `level_id`         bigint      NOT NULL AUTO_INCREMENT COMMENT '档位ID (主键)',
    `rule_kind`        varchar(8)  NOT NULL COMMENT '所属规则类别 (INSTANT/WINDOW)；与 rule_id 共同定位规则',
    `rule_id`          bigint      NOT NULL COMMENT '所属规则ID (对应 us_iot_rule_instant 或 us_iot_rule_window)',
    `level_code`       varchar(36) NOT NULL COMMENT '档位编码（业务最大30字符，含约20%物理余量）；作为输出 header 交给下游做分派',
    `severity`         smallint    NOT NULL COMMENT '严重度，越小越严重；同规则内唯一，同时也是判档顺序',
    `condition_kind`   varchar(12) NOT NULL COMMENT '条件类型 (THRESHOLD/SCRIPT)；SCRIPT 仅即时规则可用',
    `threshold_config` json       DEFAULT NULL COMMENT 'condition_kind=THRESHOLD 时必填（operator/threshold）',
    `condition_script` mediumtext DEFAULT NULL COMMENT 'condition_kind=SCRIPT 时必填：返回 Boolean 的 Groovy 裸脚本',
    `output_script` mediumtext NOT NULL COMMENT '档位跃迁时执行的 Groovy 输出脚本裸脚本体；生成供该档位所有绑定 Topic 复用的 payload',
    `cooldown_millis`  bigint     DEFAULT NULL COMMENT '可选的额外节流；档位跃迁本身已抑制持续满足的重复输出，通常不需要',
    `create_by` bigint DEFAULT NULL COMMENT '创建者用户ID',
    `create_time`      datetime   DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`level_id`),
    UNIQUE KEY `uk_rule_level_severity` (`rule_kind`, `rule_id`, `severity`),
    UNIQUE KEY `uk_rule_level_code` (`rule_kind`, `rule_id`, `level_code`),
    KEY                `idx_rule_level_rule` (`rule_kind`, `rule_id`, `severity`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='规则档位表（即时与窗口规则共用）';

-- ----------------------------
-- 4. 即时规则-产品关联表 (us_iot_rule_instant_product)
--    多对多 B 类关联：绑定=INSERT，解绑=物理 DELETE；产品必须是 product_type=1 的普通产品。
--    不设置数据库外键，与既有 schema-device.sql 一致，由管理端保存事务保证引用有效性。
-- ----------------------------
CREATE TABLE `us_iot_rule_instant_product`
(
    `id`          bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `rule_id`     bigint NOT NULL COMMENT '即时规则ID (对应 us_iot_rule_instant)',
    `product_id`  bigint NOT NULL COMMENT '普通产品ID (对应 us_iot_product，业务层强制 product_type=1)',
    `create_by`   bigint   DEFAULT NULL COMMENT '创建者用户ID',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rule_instant_product` (`rule_id`, `product_id`),
    KEY           `idx_rule_instant_product_rev` (`product_id`, `rule_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='即时规则产品关联表 (B类·仅创建审计·物理删除)';

-- ----------------------------
-- 5. 窗口规则-产品关联表 (us_iot_rule_window_product)
-- ----------------------------
CREATE TABLE `us_iot_rule_window_product`
(
    `id`          bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `rule_id`    bigint NOT NULL COMMENT '窗口规则ID (对应 us_iot_rule_window)',
    `product_id` bigint NOT NULL COMMENT '普通产品ID (对应 us_iot_product，业务层强制 product_type=1)',
    `create_by`   bigint   DEFAULT NULL COMMENT '创建者用户ID',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rule_window_product` (`rule_id`, `product_id`),
    KEY          `idx_rule_window_product_rev` (`product_id`, `rule_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='窗口规则产品关联表 (B类·仅创建审计·物理删除)';

-- ----------------------------
-- 6. Kafka 输出定义表 (us_iot_rule_kafka_output)
--    可复用的同集群精确 Topic 登记项；一个 Topic 只有一个定义、一种用途、一种编码。
--    不保存 broker/凭据/producer 参数；已被引用时只允许改 output_name。
-- ----------------------------
CREATE TABLE `us_iot_rule_kafka_output`
(
    `output_id`    bigint       NOT NULL AUTO_INCREMENT COMMENT '输出定义ID (主键)',
    `output_code`  varchar(60)  NOT NULL COMMENT '输出编码（创建后不可变；业务最大50字符，含约20%物理余量）',
    `output_name`  varchar(144) NOT NULL COMMENT '输出名称（业务最大120字符，含约20%物理余量）',
    `purpose`      varchar(14)  NOT NULL COMMENT '用途 (RULE_OUTPUT/ROUTE)；最长枚举11字符，含约20%物理余量',
    `target_topic` varchar(249) NOT NULL COMMENT '静态精确 Kafka Topic；249 为 Kafka 协议硬上限',
    `format`       varchar(14)  NOT NULL COMMENT 'value 编码 (JSON/MESSAGEPACK)；最长枚举11字符，含约20%物理余量',
    `create_by`    bigint                DEFAULT NULL COMMENT '创建者用户ID',
    `create_time`  datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`    bigint                DEFAULT NULL COMMENT '更新者用户ID',
    `update_time`  datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      bigint       NOT NULL DEFAULT 0 COMMENT '逻辑删除 (0-未删除, 非0=已删除，值为主键ID)',
    `version`      int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`output_id`),
    UNIQUE KEY `uk_rule_kafka_output_code_deleted` (`output_code`, `deleted`),
    UNIQUE KEY `uk_rule_kafka_output_topic_deleted` (`target_topic`, `deleted`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='Kafka 输出定义表（同集群精确 Topic 登记）';

-- ----------------------------
-- 7. 档位-Kafka 输出绑定表 (us_iot_rule_level_kafka_output)
--    随规则聚合整体替换：物理删除、仅创建审计。每个档位至少一条，只能绑 purpose=RULE_OUTPUT。
-- ----------------------------
CREATE TABLE `us_iot_rule_level_kafka_output`
(
    `id`          bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `level_id`    bigint NOT NULL COMMENT '档位ID (对应 us_iot_rule_level)',
    `output_id`   bigint NOT NULL COMMENT '输出定义ID (对应 us_iot_rule_kafka_output)',
    `create_by`   bigint   DEFAULT NULL COMMENT '创建者用户ID',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rule_level_kafka_output` (`level_id`, `output_id`),
    KEY           `idx_rule_level_kafka_output_rev` (`output_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='规则档位与 Kafka 输出定义绑定表 (B类·仅创建审计·物理删除)';

-- ----------------------------
-- 8. 透传路由规则表 (us_iot_rule_route)
--    产品 × 消息类型 → Kafka 输出定义；无脚本、无档位、无状态、无 revision。
--    由 engine 在时序库写入确认后原样转发；与 us_iot_rule_instant / us_iot_rule_window 不共享 ID 空间。
-- ----------------------------
CREATE TABLE `us_iot_rule_route`
(
    `rule_id`      bigint       NOT NULL AUTO_INCREMENT COMMENT '规则ID (主键；与另两张规则表不共享ID空间)',
    `rule_code`    varchar(60)  NOT NULL COMMENT '规则业务编码（创建后不可变；业务最大50字符，含约20%物理余量）',
    `rule_name`    varchar(144) NOT NULL COMMENT '规则名称（业务最大120字符，含约20%物理余量）',
    `message_type` varchar(10)  NOT NULL COMMENT '消息类型 (property/event)',
    `status`       tinyint      NOT NULL DEFAULT 0 COMMENT '规则状态 (0-停用, 1-启用；新建默认停用)',
    `create_by`    bigint                DEFAULT NULL COMMENT '创建者用户ID',
    `create_time`  datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`    bigint                DEFAULT NULL COMMENT '更新者用户ID',
    `update_time`  datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      bigint       NOT NULL DEFAULT 0 COMMENT '逻辑删除 (0-未删除, 非0=已删除，值为主键ID)',
    `version`      int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`rule_id`),
    UNIQUE KEY `uk_rule_route_code_deleted` (`rule_code`, `deleted`),
    KEY            `idx_rule_route_enabled_type` (`deleted`, `status`, `message_type`, `rule_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='透传路由规则表（无脚本·无状态）';

-- ----------------------------
-- 9. 透传路由-产品关联表 (us_iot_rule_route_product)
--    与 us_iot_rule_window_product 同构；绑定=INSERT，解绑=物理 DELETE。
-- ----------------------------
CREATE TABLE `us_iot_rule_route_product`
(
    `id`          bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `rule_id`     bigint NOT NULL COMMENT '透传路由规则ID (对应 us_iot_rule_route)',
    `product_id`  bigint NOT NULL COMMENT '普通产品ID (对应 us_iot_product，业务层强制 product_type=1)',
    `create_by`   bigint   DEFAULT NULL COMMENT '创建者用户ID',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rule_route_product` (`rule_id`, `product_id`),
    KEY           `idx_rule_route_product_rev` (`product_id`, `rule_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='透传路由产品关联表 (B类·仅创建审计·物理删除)';

-- ----------------------------
-- 10. 透传路由-Kafka 输出绑定表 (us_iot_rule_route_kafka_output)
--     与 us_iot_rule_level_kafka_output 同构，把 level_id 换成 rule_id。
-- ----------------------------
CREATE TABLE `us_iot_rule_route_kafka_output`
(
    `id`          bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `rule_id`     bigint NOT NULL COMMENT '透传路由规则ID (对应 us_iot_rule_route)',
    `output_id`   bigint NOT NULL COMMENT '输出定义ID (对应 us_iot_rule_kafka_output)',
    `create_by`   bigint   DEFAULT NULL COMMENT '创建者用户ID',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rule_route_kafka_output` (`rule_id`, `output_id`),
    KEY           `idx_rule_route_kafka_output_rev` (`output_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='透传路由与 Kafka 输出定义绑定表 (B类·仅创建审计·物理删除)';

SET
FOREIGN_KEY_CHECKS = 1;
