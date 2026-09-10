package com.unisence.iot.admin.support;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.unisence.iot.admin.config.MybatisMetaObjectHandler;
import com.unisence.iot.admin.config.MybatisPlusConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Mapper 集成测试的最小切片配置。
 * <p>
 * 只显式装载 DataSource + 事务管理 + MyBatis-Plus + 业务 Mapper；
 * Redis / Nacos / Web / 时序库等重型自动配置<b>不列即不加载</b>，从而让集成测试能在只有 MySQL 的环境里启动。
 * <ul>
 *   <li>{@link MybatisPlusConfig} —— 分页 + 乐观锁拦截器（真库才能验证的两条链路）。</li>
 *   <li>{@link MybatisMetaObjectHandler} —— 审计字段自动填充；它对"未登录"已做 try/catch 降级，
 *       在无 Sa-Token 的切片里 {@code createBy/updateBy} 填 null、其余照填，insert 走真实链路。</li>
 * </ul>
 */
@SpringBootConfiguration
@ImportAutoConfiguration({
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    JdbcTemplateAutoConfiguration.class, // 让 IT 可用 JdbcTemplate 绕过 @TableLogic 直读原始列（如软删后的 deleted 值）
    MybatisPlusAutoConfiguration.class
})
@MapperScan("com.unisence.iot.admin.mapper")
@Import({MybatisPlusConfig.class, MybatisMetaObjectHandler.class})
public class MapperItConfig {
}
