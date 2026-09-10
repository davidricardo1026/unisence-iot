-- 设备运行数据 —— GreptimeDB DDL（默认时序数据库实现）
-- 所有写入表统一使用毫秒时间索引。
-- Greptime SQL 关键字冲突列（time/event/status/value 等）一律反引号；TIME INDEX 用表级约束。
-- 物模型属性历史：4 种物理值类型 × 90/180/360 天三个固定保留档位，共 12 张静态表。
-- 物模型事件明细：每事件一张 evt_{product_key}_{identifier}（参数全部 FIELD，append_mode=true），
-- 由 TimeSeriesProvisioner.ensureEventTable 在 Admin 保存事件时同步供给，不在本文件静态建表。
-- 事件表 time / 属性表 time 均为信封发生时间 occurredAt（线上键 ts），不是 Kafka record timestamp。

CREATE
DATABASE IF NOT EXISTS unisence_iot;

USE
unisence_iot;

CREATE TABLE IF NOT EXISTS device_property_bool_90d
(
    product_key
    STRING,
    device_code
    STRING,
    identifier
    STRING,
    msg_id
    STRING,
    `value`
    BOOLEAN,
    `time`
    TIMESTAMP
(
    3
) NOT NULL, TIME INDEX
(
    `time`
), PRIMARY KEY
(
    product_key,
    device_code,
    identifier
))
    WITH ('ttl' = '90d');
CREATE TABLE IF NOT EXISTS device_property_bool_180d
(
    product_key
    STRING,
    device_code
    STRING,
    identifier
    STRING,
    msg_id
    STRING,
    `value`
    BOOLEAN,
    `time`
    TIMESTAMP
(
    3
) NOT NULL, TIME INDEX
(
    `time`
), PRIMARY KEY
(
    product_key,
    device_code,
    identifier
))
    WITH ('ttl' = '180d');
CREATE TABLE IF NOT EXISTS device_property_bool_360d
(
    product_key
    STRING,
    device_code
    STRING,
    identifier
    STRING,
    msg_id
    STRING,
    `value`
    BOOLEAN,
    `time`
    TIMESTAMP
(
    3
) NOT NULL, TIME INDEX
(
    `time`
), PRIMARY KEY
(
    product_key,
    device_code,
    identifier
))
    WITH ('ttl' = '360d');

CREATE TABLE IF NOT EXISTS device_property_long_90d
(
    product_key
    STRING,
    device_code
    STRING,
    identifier
    STRING,
    msg_id
    STRING,
    `value`
    INT64,
    `time`
    TIMESTAMP
(
    3
) NOT NULL, TIME INDEX
(
    `time`
), PRIMARY KEY
(
    product_key,
    device_code,
    identifier
))
    WITH ('ttl' = '90d');
CREATE TABLE IF NOT EXISTS device_property_long_180d
(
    product_key
    STRING,
    device_code
    STRING,
    identifier
    STRING,
    msg_id
    STRING,
    `value`
    INT64,
    `time`
    TIMESTAMP
(
    3
) NOT NULL, TIME INDEX
(
    `time`
), PRIMARY KEY
(
    product_key,
    device_code,
    identifier
))
    WITH ('ttl' = '180d');
CREATE TABLE IF NOT EXISTS device_property_long_360d
(
    product_key
    STRING,
    device_code
    STRING,
    identifier
    STRING,
    msg_id
    STRING,
    `value`
    INT64,
    `time`
    TIMESTAMP
(
    3
) NOT NULL, TIME INDEX
(
    `time`
), PRIMARY KEY
(
    product_key,
    device_code,
    identifier
))
    WITH ('ttl' = '360d');

CREATE TABLE IF NOT EXISTS device_property_double_90d
(
    product_key
    STRING,
    device_code
    STRING,
    identifier
    STRING,
    msg_id
    STRING,
    `value`
    FLOAT64,
    `time`
    TIMESTAMP
(
    3
) NOT NULL, TIME INDEX
(
    `time`
), PRIMARY KEY
(
    product_key,
    device_code,
    identifier
))
    WITH ('ttl' = '90d');
CREATE TABLE IF NOT EXISTS device_property_double_180d
(
    product_key
    STRING,
    device_code
    STRING,
    identifier
    STRING,
    msg_id
    STRING,
    `value`
    FLOAT64,
    `time`
    TIMESTAMP
(
    3
) NOT NULL, TIME INDEX
(
    `time`
), PRIMARY KEY
(
    product_key,
    device_code,
    identifier
))
    WITH ('ttl' = '180d');
CREATE TABLE IF NOT EXISTS device_property_double_360d
(
    product_key
    STRING,
    device_code
    STRING,
    identifier
    STRING,
    msg_id
    STRING,
    `value`
    FLOAT64,
    `time`
    TIMESTAMP
(
    3
) NOT NULL, TIME INDEX
(
    `time`
), PRIMARY KEY
(
    product_key,
    device_code,
    identifier
))
    WITH ('ttl' = '360d');

CREATE TABLE IF NOT EXISTS device_property_text_90d
(
    product_key
    STRING,
    device_code
    STRING,
    identifier
    STRING,
    msg_id
    STRING,
    `value`
    STRING,
    `time`
    TIMESTAMP
(
    3
) NOT NULL, TIME INDEX
(
    `time`
), PRIMARY KEY
(
    product_key,
    device_code,
    identifier
))
    WITH ('ttl' = '90d');
CREATE TABLE IF NOT EXISTS device_property_text_180d
(
    product_key
    STRING,
    device_code
    STRING,
    identifier
    STRING,
    msg_id
    STRING,
    `value`
    STRING,
    `time`
    TIMESTAMP
(
    3
) NOT NULL, TIME INDEX
(
    `time`
), PRIMARY KEY
(
    product_key,
    device_code,
    identifier
))
    WITH ('ttl' = '180d');
CREATE TABLE IF NOT EXISTS device_property_text_360d
(
    product_key
    STRING,
    device_code
    STRING,
    identifier
    STRING,
    msg_id
    STRING,
    `value`
    STRING,
    `time`
    TIMESTAMP
(
    3
) NOT NULL, TIME INDEX
(
    `time`
), PRIMARY KEY
(
    product_key,
    device_code,
    identifier
))
    WITH ('ttl' = '360d');

CREATE TABLE IF NOT EXISTS device_online_log
(
    product_key
    STRING,
    device_code
    STRING,
    `event`
    INT32,
    reason
    STRING,
    `time`
    TIMESTAMP
(
    3
) NOT NULL,
    TIME INDEX
(
    `time`
),
    PRIMARY KEY
(
    product_key,
    device_code
)
    ) WITH ('ttl' = '180d');

CREATE TABLE IF NOT EXISTS service_call_log
(
    product_key
    STRING,
    device_code
    STRING,
    identifier
    STRING,
    req_id
    STRING,
    `status`
    INT32,
    `code`
    INT32,
    `input`
    STRING,
    `output`
    STRING,
    invoke_ts
    INT64,
    `time`
    TIMESTAMP
(
    3
) NOT NULL,
    TIME INDEX
(
    `time`
),
    PRIMARY KEY
(
    product_key,
    device_code,
    identifier
)
    ) WITH ('ttl' = '180d');

CREATE TABLE IF NOT EXISTS device_alert_log
(
    product_key
    STRING,
    device_code
    STRING,
    alert_key
    STRING,
    `level`
    INT32,
    `value`
    FLOAT64,
    `message`
    STRING,
    ack
    INT32,
    `time`
    TIMESTAMP
(
    3
) NOT NULL,
    TIME INDEX
(
    `time`
),
    PRIMARY KEY
(
    product_key,
    device_code,
    alert_key
)
    ) WITH ('ttl' = '180d');

-- 物模型事件按表分钟计数（已关闭分钟；开放分钟走 Redis）。
-- time = UTC 分钟左闭端点。禁止 append_mode：同一 TAG+time 覆盖写。
-- 不补 0 行。禁止落 MySQL。写入仅 engine writeEventStats；Admin 只读。
CREATE TABLE IF NOT EXISTS us_iot_event_stats_minute
(
    product_key
    STRING,
    identifier
    STRING,
    event_count
    INT64,
    `time`
    TIMESTAMP
(
    3
) NOT NULL,
    TIME INDEX
(
    `time`
),
    PRIMARY KEY
(
    product_key,
    identifier
)
    ) WITH ('ttl' = '180d');
