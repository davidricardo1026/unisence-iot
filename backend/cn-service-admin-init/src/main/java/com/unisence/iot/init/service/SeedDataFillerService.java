package com.unisence.iot.init.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisence.iot.init.config.InitProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeedDataFillerService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InitProperties initProperties;
    private final RuleSeedDataService ruleSeedDataService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);

    @Transactional
    @SuppressWarnings("unchecked")
    public void fillSeedData() {
        log.info("Starting seed data population from separate JSON configurations in data folder...");

        try {
            assertFreshDatabase();

            // 1. 读取 data 目录下的 JSON 文件
            List<Map<String, Object>> roles;
            try (InputStream is = new ClassPathResource("data/roles.json").getInputStream()) {
                roles = objectMapper.readValue(is, List.class);
            }

            List<Map<String, Object>> menus;
            try (InputStream is = new ClassPathResource("data/menus.json").getInputStream()) {
                menus = objectMapper.readValue(is, List.class);
            }

            List<Map<String, Object>> configs;
            try (InputStream is = new ClassPathResource("data/configs.json").getInputStream()) {
                configs = objectMapper.readValue(is, List.class);
            }

            Map<String, Object> dicts;
            try (InputStream is = new ClassPathResource("data/dicts.json").getInputStream()) {
                dicts = objectMapper.readValue(is, Map.class);
            }

            List<Map<String, Object>> users;
            try (InputStream is = new ClassPathResource("data/users.json").getInputStream()) {
                users = objectMapper.readValue(is, List.class);
            }

            List<Map<String, Object>> products;
            try (InputStream is = new ClassPathResource("data/products.json").getInputStream()) {
                products = objectMapper.readValue(is, List.class);
            }

            List<Map<String, Object>> dictTypes = (List<Map<String, Object>>) dicts.get("dictTypes");
            List<Map<String, Object>> dictData = (List<Map<String, Object>>) dicts.get("dictData");

            // 2. 初始化配置的角色 (us_sys_role)。新库前置检查已通过，重复业务键应由数据库直接报错。
            log.info("Initializing system roles...");
            for (Map<String, Object> role : roles) {
                String roleName = (String) role.get("name");
                String roleCode = (String) role.get("code");
                Integer status = (Integer) role.get("status");

                log.info("Creating role dynamically: {} ({})", roleName, roleCode);
                jdbcTemplate.update(
                    "INSERT INTO us_sys_role (role_name, role_code, status) VALUES (?, ?, ?)",
                    roleName, roleCode, status != null ? status : 1
                );
            }

            // 3. 动态初始化配置的用户、对应组织结构、以及安全账号凭证。
            // 同一批多个用户可以共享部门，因此部门查询仅用于本次初始化批次内复用。
            for (Map<String, Object> user : users) {
                String username = (String) user.get("userCode");
                String realName = (String) user.get("userName");
                String plaintextPassword = (String) user.get("plaintextPassword");
                String deptName = (String) user.get("deptName");
                // 替换根部门占位符
                if ("ROOT_DEPT".equals(deptName)) {
                    deptName = initProperties.getRootDeptName();
                }
                String phone = (String) user.get("phone");
                Integer status = (Integer) user.get("status");
                List<String> roleCodes = (List<String>) user.get("roleCodes");

                // A. 校验并动态生成组织部门 (us_sys_dept)
                Long deptId = jdbcTemplate.query(
                    "SELECT dept_id FROM us_sys_dept WHERE dept_name = ? AND deleted = 0",
                    rs -> rs.next() ? rs.getLong("dept_id") : null,
                    deptName
                );
                if (deptId == null) {
                    log.info("Creating department dynamically: {}", deptName);
                    deptId = insertReturningKey(
                        "INSERT INTO us_sys_dept (parent_id, ancestors, dept_name, sort_order, leader, phone, status) " +
                            "VALUES (0, '0', ?, 1, 'Admin', ?, 1)",
                        deptName,
                        phone
                    );
                }

                // B. 校验并初始化用户主体 (us_sys_user)
                log.info("Creating user dynamically: {}", username);
                Long targetUserId = insertReturningKey(
                    "INSERT INTO us_sys_user (user_code, user_name, phone, status, dept_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                    username, realName, phone, status != null ? status : 1, deptId
                );

                // C. 初始化安全认证凭证 (us_sys_auth_identity)
                log.info("Creating auth credential for user: {}...", username);
                String frontHash = getSha256(plaintextPassword);
                String dbHash = passwordEncoder.encode(frontHash);
                jdbcTemplate.update(
                    "INSERT INTO us_sys_auth_identity (user_id, identity_type, identifier, credential) "
                        + "VALUES (?, 'local', ?, ?)",
                    targetUserId, username, dbHash
                );

                // D. 绑定角色 (us_sys_user_role)
                if (roleCodes != null && !roleCodes.isEmpty()) {
                    for (String roleCode : roleCodes) {
                        Long roleId = jdbcTemplate.query(
                            "SELECT role_id FROM us_sys_role WHERE role_code = ? AND deleted = 0",
                            rs -> rs.next() ? rs.getLong("role_id") : null,
                            roleCode
                        );
                        if (roleId != null) {
                            log.info("Binding user {} to role {}...", username, roleCode);
                            jdbcTemplate.update(
                                "INSERT INTO us_sys_user_role (user_id, role_id) VALUES (?, ?)",
                                targetUserId, roleId
                            );
                        }
                    }
                }
            }

            // 4. 全量树形菜单与按钮初始化 (us_sys_menu)
            // 菜单/按钮使用固定业务 ID（前端路由与权限点依赖其稳定）。
            log.info("Initializing system menus...");
            for (Map<String, Object> menu : menus) {
                jdbcTemplate.update(
                    "INSERT INTO us_sys_menu (menu_id, parent_id, menu_name, path, component, perms, icon, sort_order, is_visible, menu_type, module_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
                    menu.get("id"),
                    menu.get("pid"),
                    menu.get("name"),
                    menu.get("path"),
                    menu.get("comp"),
                    menu.get("perms"),
                    menu.get("icon"),
                    menu.get("sort"),
                    menu.get("type"),
                    menu.get("moduleId")
                );
            }

            // 5. 内置参数配置初始化 (us_sys_config)
            log.info("Initializing system global configurations...");
            for (Map<String, Object> config : configs) {
                jdbcTemplate.update(
                    "INSERT INTO us_sys_config (config_id, config_name, config_key, config_value, config_type) "
                        + "VALUES (?, ?, ?, ?, ?)",
                    config.get("id"), config.get("name"), config.get("key"), config.get("val"), config.get("type")
                );
            }

            // 6. 默认数据字典初始化 (us_sys_dict_type & us_sys_dict_data)
            // 使用固定业务 ID，按主键或业务唯一键判定存在性。
            log.info("Initializing data dictionary types...");
            for (Map<String, Object> dt : dictTypes) {
                jdbcTemplate.update(
                    "INSERT INTO us_sys_dict_type (dict_type_id, dict_name, dict_type, status) VALUES (?, ?, ?, 1)",
                    dt.get("id"), dt.get("name"), dt.get("type")
                );
            }

            log.info("Initializing data dictionary values...");
            for (Map<String, Object> dd : dictData) {
                jdbcTemplate.update(
                    "INSERT INTO us_sys_dict_data (dict_data_id, sort_order, dict_label, dict_value, dict_type, status) "
                        + "VALUES (?, ?, ?, ?, ?, 1)",
                    dd.get("id"),
                    dd.get("sort"),
                    dd.get("label"),
                    dd.get("val"),
                    dd.get("type")
                );
            }

            // 7. 绑定“管理员”角色与所有菜单权限 (us_sys_role_menu)
            log.info("Binding 'admin' role with all menus/permissions...");
            Long adminRoleId = jdbcTemplate.query(
                "SELECT role_id FROM us_sys_role WHERE role_code = 'admin' AND deleted = 0",
                rs -> rs.next() ? rs.getLong("role_id") : null
            );
            if (adminRoleId != null) {
                for (Map<String, Object> menu : menus) {
                    Long menuId = ((Number) menu.get("id")).longValue();
                    jdbcTemplate.update(
                        "INSERT INTO us_sys_role_menu (role_id, menu_id) VALUES (?, ?)",
                        adminRoleId, menuId
                    );
                }
            }

            // 8. 初始化一个与虚拟驱动默认配置严格对齐的测试/压测产品。
            // 设备不在此处预置：必须由驱动的 device_create 消息走真实建档链路。
            log.info("Initializing virtual-driver product and thing model...");
            Map<String, Long> productIdsByKey = new java.util.LinkedHashMap<>();
            for (Map<String, Object> product : products) {
                Long productId = insertReturningKey(
                    "INSERT INTO us_iot_product "
                        + "(product_key, product_name, node_type, net_type, online_ttl_seconds, vendor, model, "
                        + "description, attributes, device_form_schema, device_form_version, product_type) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
                    product.get("productKey"),
                    product.get("productName"),
                    product.get("nodeType"),
                    product.get("netType"),
                    product.get("onlineTtlSeconds"),
                    product.get("vendor"),
                    product.get("model"),
                    product.get("description"),
                    json(product.get("attributes")),
                    json(product.get("deviceFormSchema")),
                    product.get("deviceFormVersion")
                );
                productIdsByKey.put((String) product.get("productKey"), productId);
                for (Map<String, Object> property : children(product, "properties")) {
                    jdbcTemplate.update(
                        "INSERT INTO us_iot_tm_property "
                            + "(product_id, identifier, property_name, data_type, access_mode, unit, retention_days) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        productId,
                        property.get("identifier"),
                        property.get("propertyName"),
                        property.get("dataType"),
                        property.get("accessMode"),
                        property.get("unit"),
                        property.get("retentionDays")
                    );
                }
                for (Map<String, Object> event : children(product, "events")) {
                    jdbcTemplate.update(
                        "INSERT INTO us_iot_tm_event "
                            + "(product_id, identifier, event_name, event_type, input_params, ttl_enabled, ttl_value, ttl_unit) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        productId,
                        event.get("identifier"),
                        event.get("eventName"),
                        event.get("eventType"),
                        json(event.get("inputParams")),
                        event.get("ttlEnabled"),
                        event.get("ttlValue"),
                        event.get("ttlUnit")
                    );
                }
            }

            // 9. 初始化虚拟驱动完整链路所需的透传/即时/窗口规则。即时与窗口脚本先在同一事务内经过
            // cn-rule-sdk 沙箱预编译，任一规则失败会让本次新库初始化整体回滚。
            ruleSeedDataService.fillSeedRules(productIdsByKey);

            log.info("Successfully populated all platform seed data!");
        } catch (Exception e) {
            log.error("Failed to parse and fill seed data: {}", e.getMessage(), e);
            throw new RuntimeException("Init filler failed", e);
        }
    }

    /**
     * init 是一次性新库初始化器，不是数据修复器或可重复 seed 工具。
     *
     * <p>合法入口状态只有一种：DDL 已完整执行，所有 {@code us_*} 表为空，
     * 唯一例外是 DDL 创建的 {@code us_sys_metadata_head(MAIN, 0)} 单例种子。
     */
    private void assertFreshDatabase() {
        List<String> tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'",
            String.class
        ).stream().filter(name -> name.startsWith("us_")).toList();
        if (!tables.contains("us_sys_metadata_head")) {
            throw new IllegalStateException("数据库结构未完整创建：缺少 us_sys_metadata_head");
        }

        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
            "SELECT head_code, committed_seq, deleted FROM us_sys_metadata_head"
        );
        if (heads.size() != 1
            || !"MAIN".equals(heads.get(0).get("head_code"))
            || ((Number) heads.get(0).get("committed_seq")).longValue() != 0L
            || ((Number) heads.get(0).get("deleted")).longValue() != 0L) {
            throw new IllegalStateException(
                "数据库不是可初始化的新库：us_sys_metadata_head 必须且只能包含 MAIN/0 单例种子");
        }

        for (String table : tables) {
            if ("us_sys_metadata_head".equals(table)) {
                continue;
            }
            String safeTable = table.replace("`", "``");
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `" + safeTable + "`", Long.class);
            if (count != null && count > 0) {
                throw new IllegalStateException(
                    "数据库不是可初始化的新库：表 " + table + " 已存在 " + count + " 条数据");
            }
        }
    }

    /**
     * 执行 INSERT 并回取数据库生成的自增主键，用于外键关联。
     */
    private Long insertReturningKey(String sql, Object... params) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("No generated key returned for insert: " + sql);
        }
        return key.longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> children(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("种子 JSON 序列化失败", e);
        }
    }

    private String getSha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : encoded) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm missing", e);
        }
    }
}
