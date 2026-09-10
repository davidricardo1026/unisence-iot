package com.unisence.iot.admin.mapper;

import com.unisence.iot.admin.entity.SysUser;
import com.unisence.iot.admin.support.AbstractMysqlIntegrationTest;
import com.unisence.iot.admin.support.MapperItConfig;
import com.unisence.iot.common.service.BaseServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 逻辑删除集成测试（Testcontainers 真 MySQL）。
 * <p>
 * 验证 {@link BaseServiceImpl#removeById} 的「{@code deleted = 主键ID}」软删语义 —— 这是**只有真库能验证**的一条链路：
 * 它依赖 {@code TableInfoHelper} 解析主键列名，纯单元测试（mock mapper、无 TableInfo）跑不了。
 * <ul>
 *   <li>软删后 {@code deleted} 列的值 = 该行主键（不是固定 1/0）。</li>
 *   <li>{@code @TableLogic(value="0")} 让后续 {@code selectById} 自动追加 {@code AND deleted=0}，查不到软删行。</li>
 *   <li>唯一键 {@code (user_code, deleted)} 下，软删后同一 {@code user_code} 可再次插入（deleted 各不相同、不撞唯一键）。</li>
 * </ul>
 */
@SpringBootTest(classes = {MapperItConfig.class, LogicDeleteServiceIT.ServiceConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class LogicDeleteServiceIT extends AbstractMysqlIntegrationTest {

    @Autowired
    private ItUserService userService;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("removeById：deleted 落库为主键ID，且 selectById 因 @TableLogic 查不到")
    void removeById_setsDeletedToPrimaryKey_andHidesRow() {
        SysUser user = newUser("it_del_alice", "Alice");
        userService.save(user);
        Long id = user.getUserId();

        boolean removed = userService.removeById(id);

        assertThat(removed).isTrue();
        // 绕过 @TableLogic 直读原始列：deleted 应等于该行主键 ID（不是固定 1）
        Long rawDeleted = jdbcTemplate.queryForObject(
            "select deleted from us_sys_user where user_id = ?", Long.class, id);
        assertThat(rawDeleted).isEqualTo(id);
        // 经 MyBatis-Plus 的查询自动追加 deleted=0 → 查不到
        assertThat(userMapper.selectById(id)).isNull();
    }

    @Test
    @DisplayName("软删后可用同一 user_code 重新插入（唯一键 (user_code, deleted) 不撞）")
    void reinsertSameUserCode_afterSoftDelete_succeeds() {
        SysUser first = newUser("it_del_reuse", "First");
        userService.save(first);
        userService.removeById(first.getUserId());

        SysUser second = newUser("it_del_reuse", "Second");
        userService.save(second); // 不应抛唯一键冲突

        assertThat(second.getUserId()).isNotNull().isNotEqualTo(first.getUserId());
        SysUser active = userMapper.selectById(second.getUserId());
        assertThat(active).isNotNull();
        assertThat(active.getUserName()).isEqualTo("Second");
    }

    private static SysUser newUser(String userCode, String userName) {
        SysUser u = new SysUser();
        u.setUserCode(userCode);
        u.setUserName(userName);
        u.setStatus(1);
        u.setDeptId(1L);
        return u;
    }

    /**
     * 测试专用最小 Service：直接复用平台基类，触发真实的「deleted=主键」软删逻辑。
     */
    static class ItUserService extends BaseServiceImpl<SysUserMapper, SysUser> {
    }

    @TestConfiguration
    static class ServiceConfig {
        @Bean
        ItUserService itUserService() {
            return new ItUserService(); // baseMapper 由 ServiceImpl 的 @Autowired 注入
        }
    }
}
