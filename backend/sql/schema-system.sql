-- =================================================================================
-- UNISENCE (统一互联) 平台系统管理模块数据库表结构 (MySQL DDL 脚本)
--
-- 本文件仅含 us_sys_* 业务表定义。枚举与引用有效性由应用层校验，DDL 不写 CHECK / FOREIGN KEY。
-- =================================================================================

SET NAMES utf8mb4;
SET
FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- 1. 部门/组织树表 (us_sys_dept)
-- ----------------------------
CREATE TABLE `us_sys_dept`
(
    `dept_id`     bigint       NOT NULL AUTO_INCREMENT COMMENT '部门ID (主键)',
    `parent_id`   bigint       NOT NULL DEFAULT 0 COMMENT '父部门ID (0表示顶级部门)',
    `ancestors`   varchar(500) NOT NULL DEFAULT '' COMMENT '祖级列表 (逗号分隔，便于层级检索，如 0,1,2)',
    `dept_name`   varchar(100) NOT NULL COMMENT '部门名称',
    `sort_order` int             DEFAULT 0 COMMENT '显示顺序',
    `leader` varchar(50) DEFAULT NULL COMMENT '负责人姓名（最大50字符）',
    `phone`  varchar(20) DEFAULT NULL COMMENT '联系电话（含国际区号最大20字符）',
    `status`      tinyint      NOT NULL DEFAULT 1 COMMENT '部门状态 (0-禁用, 1-正常)',
    `create_by`   bigint                DEFAULT NULL COMMENT '创建者用户ID',
    `create_time` datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`   bigint                DEFAULT NULL COMMENT '更新者用户ID',
    `update_time` datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`    bigint NOT NULL DEFAULT 0 COMMENT '逻辑删除 (0-未删除, 非0=已删除，值为主键ID)',
    `version`    int    NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`dept_id`),
    KEY          `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci COMMENT='部门组织树表';

-- ----------------------------
-- 2. 用户主体表 (us_sys_user)
-- ----------------------------
CREATE TABLE `us_sys_user`
(
    `user_id`   bigint      NOT NULL AUTO_INCREMENT COMMENT '用户ID (主键，数据库自增)',
    `user_code` varchar(50) NOT NULL COMMENT '用户唯一登录账号（最大50字符）',
    `user_name` varchar(50) NOT NULL COMMENT '用户名称（最大50字符）',
    `phone`     varchar(20) DEFAULT NULL COMMENT '手机号（含国际区号最大20字符）',
    `status`      tinyint     NOT NULL DEFAULT 1 COMMENT '账号状态 (0-禁用, 1-正常)',
    `dept_id`     bigint      NOT NULL COMMENT '所属部门ID (对应us_sys_dept)',
    `create_by`   bigint               DEFAULT NULL COMMENT '创建者用户ID',
    `create_time` datetime             DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`   bigint               DEFAULT NULL COMMENT '更新者用户ID',
    `update_time` datetime             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`   bigint      NOT NULL DEFAULT 0 COMMENT '逻辑删除 (0-未删除, 非0=已删除，值为主键ID)',
    `version`   int         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`user_id`),
    UNIQUE KEY `uk_user_code_deleted` (`user_code`, `deleted`),
    KEY         `idx_dept_id` (`dept_id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci COMMENT='用户主体表';

-- ----------------------------
-- 3. 用户认证凭证表 (us_sys_auth_identity)
-- ----------------------------
CREATE TABLE `us_sys_auth_identity`
(
    `identity_id`   bigint       NOT NULL AUTO_INCREMENT COMMENT '凭证ID (主键)',
    `user_id`       bigint       NOT NULL COMMENT '关联用户主体ID (对应us_sys_user)',
    `identity_type` varchar(30)  NOT NULL COMMENT '认证渠道类型代码（最大30字符）',
    `identifier`    varchar(100) NOT NULL COMMENT '渠道唯一标识（最大100字符）',
    `credential`  varchar(255)    DEFAULT NULL COMMENT '凭证密文/密码哈希/令牌占位符',
    `create_by`   bigint          DEFAULT NULL COMMENT '创建者用户ID',
    `create_time` datetime        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`   bigint          DEFAULT NULL COMMENT '更新者用户ID',
    `update_time` datetime        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     bigint NOT NULL DEFAULT 0 COMMENT '逻辑删除 (0-未删除, 非0=已删除，值为主键ID)',
    `version`     int    NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`identity_id`),
    UNIQUE KEY `uk_identity_type_identifier_deleted` (`identity_type`, `identifier`, `deleted`),
    KEY           `idx_user_id` (`user_id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci COMMENT='用户认证凭证表';

-- ----------------------------
-- 4. 角色表 (us_sys_role)
-- ----------------------------
CREATE TABLE `us_sys_role`
(
    `role_id`     bigint       NOT NULL AUTO_INCREMENT COMMENT '角色ID (主键)',
    `role_name`   varchar(100) NOT NULL COMMENT '角色名称',
    `role_code`   varchar(100) NOT NULL COMMENT '角色全局唯一编码 (如 ROLE_ADMIN, ROLE_OPERATOR)',
    `status`      tinyint      NOT NULL DEFAULT 1 COMMENT '角色状态 (0-禁用, 1-正常)',
    `create_by`   bigint                DEFAULT NULL COMMENT '创建者用户ID',
    `create_time` datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`   bigint                DEFAULT NULL COMMENT '更新者用户ID',
    `update_time` datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` bigint NOT NULL DEFAULT 0 COMMENT '逻辑删除 (0-未删除, 非0=已删除，值为主键ID)',
    `version` int    NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`role_id`),
    UNIQUE KEY `uk_role_code_deleted` (`role_code`, `deleted`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci COMMENT='角色表';

-- ----------------------------
-- 5. 菜单与权限点表 (us_sys_menu)
-- ----------------------------
CREATE TABLE `us_sys_menu`
(
    `menu_id`     bigint       NOT NULL AUTO_INCREMENT COMMENT '菜单ID (主键)',
    `parent_id`   bigint       NOT NULL DEFAULT 0 COMMENT '父菜单ID (0表示顶级)',
    `menu_name`   varchar(100) NOT NULL COMMENT '菜单/按钮名称',
    `path`        varchar(255)          DEFAULT NULL COMMENT '前端路由访问路径 (菜单或目录使用)',
    `component`   varchar(255)          DEFAULT NULL COMMENT '前端组件路径 (菜单使用)',
    `perms` varchar(100) DEFAULT NULL COMMENT '权限字符串（最大100字符）',
    `icon`        varchar(100)          DEFAULT NULL COMMENT '菜单图标',
    `sort_order`  int                   DEFAULT 0 COMMENT '显示排序',
    `is_visible` tinyint NOT NULL DEFAULT 1 COMMENT '是否可见 (0-隐藏, 1-显示；控制是否在导航中展示)',
    `menu_type`  char(1) NOT NULL COMMENT '类型 (D-模块, M-目录, C-菜单, F-按钮)',
    `module_id`  bigint           DEFAULT NULL COMMENT '所属模块ID (冗余字段，方便查询)',
    `create_by`   bigint                DEFAULT NULL COMMENT '创建者用户ID',
    `create_time` datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`   bigint                DEFAULT NULL COMMENT '更新者用户ID',
    `update_time` datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`    bigint  NOT NULL DEFAULT 0 COMMENT '逻辑删除 (0-未删除, 非0=已删除，值为主键ID)',
    `version`    int     NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`menu_id`),
    KEY          `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci COMMENT='菜单与权限点表';

-- ----------------------------
-- 6. 用户角色关联表 (us_sys_user_role)
-- ----------------------------
CREATE TABLE `us_sys_user_role`
(
    `user_role_id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户角色关联ID (主键)',
    `user_id`      bigint NOT NULL COMMENT '用户ID',
    `role_id`      bigint NOT NULL COMMENT '角色ID',
    `create_by`    bigint   DEFAULT NULL COMMENT '创建者用户ID',
    `create_time`  datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`user_role_id`),
    UNIQUE KEY `uk_user_role` (`user_id`, `role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户角色中间表 (C类·仅创建审计·物理删除：解绑=真删)';

-- ----------------------------
-- 7. 角色菜单关联表 (us_sys_role_menu)
-- ----------------------------
CREATE TABLE `us_sys_role_menu`
(
    `role_menu_id` bigint NOT NULL AUTO_INCREMENT COMMENT '角色菜单关联ID (主键)',
    `role_id`      bigint NOT NULL COMMENT '角色ID',
    `menu_id`      bigint NOT NULL COMMENT '菜单ID',
    `create_by`    bigint   DEFAULT NULL COMMENT '创建者用户ID',
    `create_time`  datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`role_menu_id`),
    UNIQUE KEY `uk_role_menu` (`role_id`, `menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色菜单中间表 (C类·仅创建审计·物理删除：解绑=真删)';

-- ----------------------------
-- 8. 字典类型表 (us_sys_dict_type)
-- ----------------------------
CREATE TABLE `us_sys_dict_type`
(
    `dict_type_id` bigint       NOT NULL AUTO_INCREMENT COMMENT '字典类型ID (主键)',
    `dict_name`    varchar(100) NOT NULL COMMENT '字典名称',
    `dict_type`    varchar(100) NOT NULL COMMENT '字典类型 (全局唯一大类，如 device_status)',
    `status`       tinyint      NOT NULL DEFAULT 1 COMMENT '字典状态 (0-禁用, 1-正常)',
    `create_by`    bigint                DEFAULT NULL COMMENT '创建者用户ID',
    `create_time`  datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`    bigint                DEFAULT NULL COMMENT '更新者用户ID',
    `update_time`  datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` bigint NOT NULL DEFAULT 0 COMMENT '逻辑删除 (0-未删除, 非0=已删除，值为主键ID)',
    `version` int    NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`dict_type_id`),
    UNIQUE KEY `uk_dict_type_deleted` (`dict_type`, `deleted`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci COMMENT='字典类型表';

-- ----------------------------
-- 9. 字典数据表 (us_sys_dict_data)
-- ----------------------------
CREATE TABLE `us_sys_dict_data`
(
    `dict_data_id` bigint       NOT NULL AUTO_INCREMENT COMMENT '字典数据ID (主键)',
    `sort_order` int             DEFAULT 0 COMMENT '字典排序',
    `dict_label`   varchar(100) NOT NULL COMMENT '字典标签 (前端展示文本，如 正常)',
    `dict_value`   varchar(100) NOT NULL COMMENT '字典键值 (数据库实际存放，如 1)',
    `dict_type`    varchar(100) NOT NULL COMMENT '所属字典类型 (关联us_sys_dict_type)',
    `status`       tinyint      NOT NULL DEFAULT 1 COMMENT '数据状态 (0-禁用, 1-正常)',
    `create_by`    bigint                DEFAULT NULL COMMENT '创建者用户ID',
    `create_time`  datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`    bigint                DEFAULT NULL COMMENT '更新者用户ID',
    `update_time`  datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`    bigint NOT NULL DEFAULT 0 COMMENT '逻辑删除 (0-未删除, 非0=已删除，值为主键ID)',
    `version`    int    NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`dict_data_id`),
    UNIQUE KEY `uk_dict_type_value_deleted` (`dict_type`, `dict_value`, `deleted`),
    KEY          `idx_dict_type` (`dict_type`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci COMMENT='字典数据表';

-- ----------------------------
-- 10. 全局参数设置表 (us_sys_config)
-- ----------------------------
CREATE TABLE `us_sys_config`
(
    `config_id`    bigint       NOT NULL AUTO_INCREMENT COMMENT '参数主键',
    `config_name`  varchar(100) NOT NULL COMMENT '参数名称',
    `config_key`   varchar(100) NOT NULL COMMENT '参数键名 (全局唯一标识，如 sys.user.initPassword)',
    `config_value` varchar(500) NOT NULL COMMENT '参数键值',
    `config_type`  tinyint      NOT NULL DEFAULT 0 COMMENT '是否系统内置 (0-否, 1-是)',
    `create_by`    bigint                DEFAULT NULL COMMENT '创建者用户ID',
    `create_time`  datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`    bigint                DEFAULT NULL COMMENT '更新者用户ID',
    `update_time`  datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` bigint NOT NULL DEFAULT 0 COMMENT '逻辑删除 (0-未删除, 非0=已删除，值为主键ID)',
    `version` int    NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`config_id`),
    UNIQUE KEY `uk_config_key_deleted` (`config_key`, `deleted`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci COMMENT='参数配置表';

-- ----------------------------
-- 11. 操作日志表 (us_sys_oper_log)
-- ----------------------------
CREATE TABLE `us_sys_oper_log`
(
    `oper_log_id`    bigint NOT NULL AUTO_INCREMENT COMMENT '操作日志主键 (主键)',
    `title`          varchar(50)  DEFAULT '' COMMENT '模块标题 (如 用户管理)',
    `business_type`  int          DEFAULT 0 COMMENT '业务类型 (0-其它, 1-新增, 2-修改, 3-删除, 4-授权, 5-强退)',
    `method`         varchar(255) DEFAULT '' COMMENT '执行的方法名 (类名+方法名)',
    `request_method` varchar(10)  DEFAULT '' COMMENT '请求方式 (如 POST, PUT, DELETE)',
    `operator_code` varchar(50) DEFAULT '' COMMENT '操作人员账号（最大50字符）',
    `oper_ip`       varchar(45) DEFAULT '' COMMENT '操作IP地址（IPv6 文本最大45字符）',
    `oper_url`       varchar(255) DEFAULT '' COMMENT '请求URL',
    `oper_param`     text COMMENT '请求参数 (JSON字符串，规则脚本等核心数据必须完整保存)',
    `json_result`    text COMMENT '响应结果 (返回JSON)',
    `status`         tinyint      DEFAULT 1 COMMENT '操作状态 (0-异常, 1-正常)',
    `error_msg`      text COMMENT '错误消息 (异常信息记录)',
    `create_by`      bigint       DEFAULT NULL COMMENT '创建者用户ID',
    `create_time`    datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`oper_log_id`),
    KEY             `idx_operator_code` (`operator_code`),
    KEY              `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='操作日志表';

-- ----------------------------
-- 12. 登录日志表 (us_sys_login_log)
-- ----------------------------
CREATE TABLE `us_sys_login_log`
(
    `login_log_id`   bigint NOT NULL AUTO_INCREMENT COMMENT '登录日志主键 (主键)',
    `user_code` varchar(50)  DEFAULT '' COMMENT '登录账号（最大50字符）',
    `ipaddr`    varchar(45)  DEFAULT '' COMMENT '登录IP地址（IPv6 文本最大45字符）',
    `login_location` varchar(255) DEFAULT '' COMMENT '登录地点',
    `browser`   varchar(100) DEFAULT '' COMMENT '浏览器类型（最大100字符）',
    `os`        varchar(100) DEFAULT '' COMMENT '操作系统（最大100字符）',
    `status`         tinyint      DEFAULT 1 COMMENT '登录状态 (0-失败, 1-成功)',
    `msg`            varchar(255) DEFAULT '' COMMENT '提示消息 (如：密码错误)',
    `login_time`     datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
    `create_by`      bigint       DEFAULT NULL COMMENT '创建者用户ID',
    `create_time`    datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`login_log_id`),
    KEY         `idx_user_code` (`user_code`),
    KEY              `idx_login_time` (`login_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='系统登录日志表';

-- ----------------------------
-- 13. 文件信息存储表 (us_sys_file_info)
-- ----------------------------
CREATE TABLE `us_sys_file_info`
(
    `file_info_id` bigint       NOT NULL AUTO_INCREMENT COMMENT '文件信息记录ID (主键)',
    `file_name`    varchar(255) NOT NULL COMMENT '文件原始名称',
    `bucket_name`  varchar(20) NOT NULL COMMENT '逻辑文件分类（general、avatars、product-images）',
    `object_name`  varchar(255) NOT NULL COMMENT '存储对象唯一键（UUID/随机串+原后缀）',
    `file_size`    bigint       NOT NULL COMMENT '文件大小 (Bytes)',
    `file_suffix` varchar(10)           DEFAULT NULL COMMENT '文件后缀名 (如 xlsx, png)',
    `content_type` varchar(100) DEFAULT NULL COMMENT '文件 MIME 类型（最大100字符）',
    `file_url`     varchar(255) DEFAULT NULL COMMENT '稳定文件访问 URL（插入取得ID后回填，最大255字符）',
    `create_by`   bigint                DEFAULT NULL COMMENT '创建者用户ID (即上传者ID)',
    `create_time` datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`   bigint                DEFAULT NULL COMMENT '更新者用户ID',
    `update_time` datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     bigint       NOT NULL DEFAULT 0 COMMENT '逻辑删除 (0-未删除, 非0=已删除，值为主键ID)',
    `version`     int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`file_info_id`),
    UNIQUE KEY `uk_object_name_deleted` (`object_name`, `deleted`),
    KEY           `idx_file_name` (`file_name`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci COMMENT='系统文件元数据表';

-- ----------------------------
-- 14. 元数据提交水位表 (us_sys_metadata_head)
--     全局单例行（head_code='MAIN'），committed_seq 是唯一权威提交水位。
--     取水位固定用 UPDATE ... SET committed_seq = LAST_INSERT_ID(committed_seq + 1)，
--     单例行更新锁持有到事务结束，因此不同写入实例取号顺序 == 提交顺序。
--     不按 meta_key 拆分：只有全局水位才能表达「一个事务跨多个元数据域」的原子边界。
-- ----------------------------
CREATE TABLE `us_sys_metadata_head`
(
    `metadata_head_id` bigint  NOT NULL AUTO_INCREMENT COMMENT '主键',
    `head_code`        char(4) NOT NULL COMMENT '水位标识，固定 MAIN（全局唯一单例行）',
    `committed_seq`    bigint  NOT NULL DEFAULT 0 COMMENT '已完整提交的全局序号 (从0开始，只增不减；所有engine最终必须达到)',
    `create_by`        bigint           DEFAULT NULL COMMENT '创建者用户ID',
    `create_time`      datetime         DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`        bigint           DEFAULT NULL COMMENT '最近一次推进水位的用户ID',
    `update_time`      datetime         DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          bigint  NOT NULL DEFAULT 0 COMMENT '逻辑删除 (0-未删除；单例行禁止删除)',
    `version`          int     NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`metadata_head_id`),
    UNIQUE KEY `uk_metadata_head_code_deleted` (`head_code`, `deleted`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci COMMENT='元数据提交水位表 (全局单例行)';

-- 初始化唯一一行；committed_seq 从 0 开始
INSERT INTO `us_sys_metadata_head` (`head_code`, `committed_seq`)
VALUES ('MAIN', 0);

-- ----------------------------
-- 15. 元数据变更目录表 (us_sys_metadata_change)
--     C 类只增流水：只记录「哪个聚合根失效了」，不复制任何业务值。
--     与业务 DML 同一事务提交，消除「业务成功但变更记录丢失」的双写窗口。
--     change_id 不可当提交游标——并发事务可能先取小自增ID却后提交，engine 越过即永久漏读；
--     游标只能是由单例行锁串行分配的 commit_seq。
-- ----------------------------
CREATE TABLE `us_sys_metadata_change`
(
    `change_id`   bigint      NOT NULL AUTO_INCREMENT COMMENT '主键 (仅作行身份，禁止当提交游标)',
    `commit_seq`  bigint      NOT NULL COMMENT '所属业务写事务的提交序号 (来自us_sys_metadata_head)',
    `meta_key`    varchar(18) NOT NULL COMMENT '元数据域 (IOT_PRODUCT/IOT_DEVICE/IOT_THING_MODEL/IOT_RULES；最长15字符含约20%余量)',
    `scope_id`    bigint      NOT NULL COMMENT '受影响聚合根ID；0 专用于该 meta_key 全域强制重建',
    `create_by`   bigint DEFAULT NULL COMMENT '触发业务变更或手动重建的用户ID',
    `create_time` datetime(3)          DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事务记录时间',
    PRIMARY KEY (`change_id`),
    UNIQUE KEY `uk_metadata_change_scope` (`commit_seq`, `meta_key`, `scope_id`),
    KEY           `idx_metadata_change_commit` (`commit_seq`, `change_id`),
    KEY           `idx_metadata_change_scope` (`meta_key`, `scope_id`, `commit_seq`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci COMMENT='元数据变更目录表 (C类·只增流水·按commit_seq前缀清理)';

SET
FOREIGN_KEY_CHECKS = 1;
