package com.unisence.iot.admin.system.service;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.unisence.iot.admin.system.dto.UserOnlineQuery;
import com.unisence.iot.admin.system.vo.SysUserOnlineVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static com.unisence.iot.admin.system.service.SysUserOnlineService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@link SysUserOnlineServiceImpl} 单元测试（数据源：Sa-Token 托管的 Redis 会话）。
 * <p>
 * 用 {@code mockStatic(StpUtil.class)} 隔离 Sa-Token 静态调用，并临时替换公有静态字段 {@code StpUtil.stpLogic}
 * 为 mock（@AfterEach 还原，避免污染其它测试）。覆盖：会话→VO 映射、失效会话跳过、userCode 模糊 / ipaddr 精确过滤
 * （契约 §1.5）、内存分页、强退委托。
 */
class SysUserOnlineServiceImplTest {

    private static final String PREFIX = "Authorization:login:token:";

    private final SysUserOnlineServiceImpl service = new SysUserOnlineServiceImpl();
    private StpLogic originalStpLogic;

    @BeforeEach
    void saveStpLogic() {
        originalStpLogic = StpUtil.stpLogic;
    }

    @AfterEach
    void restoreStpLogic() {
        StpUtil.stpLogic = originalStpLogic;
    }

    @Test
    @DisplayName("listOnlineUsers：活跃会话映射为 VO（token 去前缀），失效会话跳过，total=有效会话数")
    void listOnlineUsers_mapsActive_skipsExpired() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            StpLogic stpLogic = mock(StpLogic.class);
            StpUtil.stpLogic = stpLogic;
            when(stpLogic.splicingKeyTokenValue("")).thenReturn(PREFIX);
            stp.when(() -> StpUtil.searchTokenValue("", 0, -1, false))
                .thenReturn(List.of(PREFIX + "uuid-a", PREFIX + "uuid-b"));

            SaSession active = session("admin", "10.0.0.1", "管理员", "内网", "Chrome", "Linux");
            when(stpLogic.getTokenSessionByToken("uuid-a", false)).thenReturn(active);
            when(stpLogic.getTokenSessionByToken("uuid-b", false)).thenReturn(null); // 失效 → 跳过

            PageResult<SysUserOnlineVO> res = service.listOnlineUsers(req(1, 10, new UserOnlineQuery()));

            assertThat(res.total()).isEqualTo(1);
            assertThat(res.list()).hasSize(1);
            SysUserOnlineVO vo = res.list().get(0);
            assertThat(vo.getTokenId()).isEqualTo("uuid-a"); // 去掉 Redis Key 前缀
            assertThat(vo.getUserCode()).isEqualTo("admin");
            assertThat(vo.getIpaddr()).isEqualTo("10.0.0.1");
            assertThat(vo.getBrowser()).isEqualTo("Chrome");
            assertThat(vo.getOs()).isEqualTo("Linux");
            assertThat(vo.getLoginTime()).isNotNull();
        }
    }

    @Test
    @DisplayName("过滤：userCode 模糊匹配（contains）、ipaddr 精确匹配（契约 §1.5）")
    void listOnlineUsers_userCodeFuzzy_ipExact() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            StpLogic stpLogic = mock(StpLogic.class);
            StpUtil.stpLogic = stpLogic;
            when(stpLogic.splicingKeyTokenValue("")).thenReturn(PREFIX);
            stp.when(() -> StpUtil.searchTokenValue("", 0, -1, false))
                .thenReturn(List.of(PREFIX + "uuid-a"));
            SaSession active = session("admin", "10.0.0.1", "管理员", "内网", "Chrome", "Linux");
            when(stpLogic.getTokenSessionByToken("uuid-a", false)).thenReturn(active);

            // userCode 模糊："dmi" 是 "admin" 的子串 → 命中
            assertThat(service.listOnlineUsers(req(1, 10, query("dmi", null))).total()).isEqualTo(1);
            // userCode 模糊：不含 → 0
            assertThat(service.listOnlineUsers(req(1, 10, query("root", null))).total()).isZero();
            // ipaddr 精确：完全相等 → 命中
            assertThat(service.listOnlineUsers(req(1, 10, query(null, "10.0.0.1"))).total()).isEqualTo(1);
            // ipaddr 精确：前缀相同但不全等 → 0（非模糊）
            assertThat(service.listOnlineUsers(req(1, 10, query(null, "10.0.0"))).total()).isZero();
        }
    }

    @Test
    @DisplayName("内存分页：total=全量会话数，records=当前页大小")
    void listOnlineUsers_paginatesInMemory() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            StpLogic stpLogic = mock(StpLogic.class);
            StpUtil.stpLogic = stpLogic;
            when(stpLogic.splicingKeyTokenValue("")).thenReturn(PREFIX);
            stp.when(() -> StpUtil.searchTokenValue("", 0, -1, false))
                .thenReturn(List.of(PREFIX + "uuid-a", PREFIX + "uuid-b", PREFIX + "uuid-c"));
            SaSession active = session("admin", "10.0.0.1", "管理员", "内网", "Chrome", "Linux");
            when(stpLogic.getTokenSessionByToken(anyString(), eq(false))).thenReturn(active);

            PageResult<SysUserOnlineVO> res = service.listOnlineUsers(req(1, 2, new UserOnlineQuery()));

            assertThat(res.total()).isEqualTo(3); // 全量
            assertThat(res.list()).hasSize(2);     // 当前页 LIMIT 2
        }
    }

    @Test
    @DisplayName("kickout：委托 StpUtil.logoutByTokenValue(原始 token)")
    void kickout_delegatesToLogout() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            service.kickout("uuid-x");
            stp.verify(() -> StpUtil.logoutByTokenValue("uuid-x"));
        }
    }

    // ---- helpers ----

    private static SaSession session(String userCode, String ip, String userName,
                                     String location, String browser, String os) {
        SaSession s = mock(SaSession.class);
        when(s.getString(SESSION_KEY_USER_CODE)).thenReturn(userCode);
        when(s.getString(SESSION_KEY_IP)).thenReturn(ip);
        when(s.getString(SESSION_KEY_USER_NAME)).thenReturn(userName);
        when(s.getString(SESSION_KEY_LOCATION)).thenReturn(location);
        when(s.getString(SESSION_KEY_BROWSER)).thenReturn(browser);
        when(s.getString(SESSION_KEY_OS)).thenReturn(os);
        when(s.getCreateTime()).thenReturn(System.currentTimeMillis());
        return s;
    }

    private static UserOnlineQuery query(String userCode, String ipaddr) {
        UserOnlineQuery q = new UserOnlineQuery();
        q.setUserCode(userCode);
        q.setIpaddr(ipaddr);
        return q;
    }

    private static PageRequest<UserOnlineQuery> req(int pageNum, int pageSize, UserOnlineQuery query) {
        return PageRequest.of(pageNum, pageSize, query);
    }
}
