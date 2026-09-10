package com.unisence.iot.admin.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unisence.iot.admin.entity.SysUser;
import com.unisence.iot.admin.support.AbstractMysqlIntegrationTest;
import com.unisence.iot.admin.support.MapperItConfig;
import com.unisence.iot.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SysUserMapper} 集成测试（Testcontainers 真 MySQL，验证真实 SQL、拦截器与落库）。
 * <p>
 * 类级 {@code @Transactional} → 每个用例结束自动回滚，容器不留痕、用例互不污染。
 * 运行：{@code gradle :cn-service-admin:integrationTest}（需要本机 docker）。
 */
@SpringBootTest(classes = MapperItConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class SysUserMapperIT extends AbstractMysqlIntegrationTest {

    @Autowired
    private SysUserMapper userMapper;

    @Test
    @DisplayName("insert + selectById：真库往返，自增主键回填")
    void insertThenSelectById_roundTrips() {
        SysUser user = newUser("it_alice", "Alice");

        userMapper.insert(user);

        assertThat(user.getUserId()).isNotNull(); // MyBatis-Plus 回填数据库自增 id
        SysUser found = userMapper.selectById(user.getUserId());
        assertThat(found).isNotNull();
        assertThat(found.getUserCode()).isEqualTo("it_alice");
        // 审计字段由 MybatisMetaObjectHandler 自动填充（未登录 → createBy 为 null，其余照填）
        assertThat(found.getCreateTime()).isNotNull();
        assertThat(found.getDeleted()).isEqualTo(0L);
        assertThat(found.getVersion()).isZero();
    }

    @Test
    @DisplayName("selectList + like：验证查询条件真的转成正确 SQL 并过滤")
    void selectList_filtersByUserCodeLike() {
        userMapper.insert(newUser("it_alice", "Alice"));
        userMapper.insert(newUser("it_bob", "Bob"));

        List<SysUser> hit = userMapper.selectList(
            new LambdaQueryWrapper<SysUser>().like(SysUser::getUserCode, "alice"));

        assertThat(hit).extracting(SysUser::getUserCode).containsExactly("it_alice");
    }

    @Test
    @DisplayName("selectPage：PaginationInnerInterceptor 真的分页（total 与 records 都对）")
    void selectPage_appliesPaginationInterceptor() {
        for (int i = 0; i < 5; i++) {
            userMapper.insert(newUser("it_page_" + i, "P" + i));
        }

        Page<SysUser> page = userMapper.selectPage(
            Page.of(1, 2),
            new LambdaQueryWrapper<SysUser>().likeRight(SysUser::getUserCode, "it_page_"));

        assertThat(page.getTotal()).isEqualTo(5);   // COUNT 查询由拦截器自动生成
        assertThat(page.getRecords()).hasSize(2);   // LIMIT 只取当页 2 条
        assertThat(page.getPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("updateById 乐观锁：过期版本的并发更新影响 0 行（OptimisticLockerInnerInterceptor）")
    void updateById_staleVersionAffectsZeroRows() {
        SysUser user = newUser("it_lock", "Lock");
        userMapper.insert(user);
        Long id = user.getUserId();

        // 客户端 A：正常加载并更新，version 0 → 1
        SysUser writerA = userMapper.selectById(id);
        assertThat(writerA.getVersion()).isZero();
        writerA.setUserName("A-first");
        int firstRows = userMapper.updateById(writerA);
        assertThat(firstRows).isEqualTo(1);

        // 客户端 B：手工构造一个仍持 version=0 的过期副本（独立对象，避开 MyBatis 一级缓存把 A 的 version 回写共享实例）
        SysUser writerB = staleCopy(id, "B-stale");
        int secondRows = userMapper.updateById(writerB);
        assertThat(secondRows).isZero(); // WHERE version=0 已不匹配（DB 已是 1）→ 0 行

        // 落库结果是 A 的值，version=1
        SysUser reloaded = userMapper.selectById(id);
        assertThat(reloaded.getUserName()).isEqualTo("A-first");
        assertThat(reloaded.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("updateByIdWithVersionCheck：过期版本命中 0 行时抛 HTTP 409 / code 2040")
    void updateByIdWithVersionCheck_onStale_throws409() {
        SysUser user = newUser("it_409", "Conflict");
        userMapper.insert(user);
        Long id = user.getUserId();

        // A 先成功更新，把 version 顶到 1
        SysUser writerA = userMapper.selectById(id);
        writerA.setUserName("A-win");
        userMapper.updateByIdWithVersionCheck(writerA);

        // B 拿过期 version=0 的独立副本再走带校验的包装方法 → 受影响 0 行 → BusinessException(409, 2040)
        SysUser writerB = staleCopy(id, "B-lose");
        assertThatThrownBy(() -> userMapper.updateByIdWithVersionCheck(writerB))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> {
                BusinessException be = (BusinessException) ex;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(be.getCode()).isEqualTo(2040);
            });
    }

    /**
     * 手工构造一个「已过期」的独立副本：显式 version=0，不经 selectById，避免一级缓存共享实例导致 version 被回写。
     */
    private static SysUser staleCopy(Long id, String userName) {
        SysUser u = new SysUser();
        u.setUserId(id);
        u.setUserName(userName);
        u.setVersion(0);
        return u;
    }

    private static SysUser newUser(String userCode, String userName) {
        SysUser u = new SysUser();
        u.setUserCode(userCode);
        u.setUserName(userName);
        u.setStatus(1);
        u.setDeptId(1L); // dept_id 非空（仅索引、非外键，给任意值即可）
        return u;
    }
}
