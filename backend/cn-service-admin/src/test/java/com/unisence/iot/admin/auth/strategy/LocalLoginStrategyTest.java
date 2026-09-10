package com.unisence.iot.admin.auth.strategy;

import com.unisence.iot.admin.entity.SysAuthIdentity;
import com.unisence.iot.admin.entity.SysUser;
import com.unisence.iot.admin.mapper.SysAuthIdentityMapper;
import com.unisence.iot.admin.mapper.SysUserMapper;
import com.unisence.iot.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link LocalLoginStrategy} 单元测试（登录安全核心）。
 * <p>
 * 纯 Mockito 单测：mock 两个 Mapper + 真实 {@link BCryptPasswordEncoder}（strength=10，与种子灌注一致），
 * 不加载 Spring 上下文、不碰 DB / Sa-Token。
 * <p>
 * 复刻线上双层哈希模型：前端传入 {@code SHA-256(明文)}，库中存 {@code BCrypt(SHA-256串)}；
 * 认证即 {@code passwordEncoder.matches(前端SHA串, 库中BCrypt串)}。
 */
class LocalLoginStrategyTest {

    private static final String USERNAME = "admin";
    private static final String PLAINTEXT = "admin123";

    private SysUserMapper userMapper;
    private SysAuthIdentityMapper authIdentityMapper;
    private PasswordEncoder passwordEncoder;
    private LocalLoginStrategy strategy;

    private String frontHash;         // 前端 SHA-256(明文)
    private String storedCredential;  // 库中 BCrypt(frontHash)

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        authIdentityMapper = mock(SysAuthIdentityMapper.class);
        passwordEncoder = new BCryptPasswordEncoder(10);
        strategy = new LocalLoginStrategy(userMapper, authIdentityMapper, passwordEncoder);

        frontHash = sha256(PLAINTEXT);
        storedCredential = passwordEncoder.encode(frontHash);
    }

    @Test
    @DisplayName("凭证正确 → 返回 userId")
    void authenticate_success_returnsUserId() {
        when(userMapper.selectOne(any())).thenReturn(enabledUser(2L));
        when(authIdentityMapper.selectOne(any())).thenReturn(identityWith(storedCredential));

        Long userId = strategy.authenticate(params(USERNAME, frontHash));

        assertThat(userId).isEqualTo(2L);
    }

    @Test
    @DisplayName("用户不存在 → 401/1002 统一文案（防枚举），且不查凭证")
    void authenticate_userNotFound_unauthorized() {
        when(userMapper.selectOne(any())).thenReturn(null);

        assertBusinessError(() -> strategy.authenticate(params(USERNAME, frontHash)),
                            HttpStatus.UNAUTHORIZED, 1002, "用户名或密码错误");

        // 用户不存在时应短路，不应再去查凭证表
        verify(authIdentityMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("账号被禁用（status=0）→ 403/1003")
    void authenticate_disabledUser_forbidden() {
        SysUser disabled = enabledUser(2L);
        disabled.setStatus(0);
        when(userMapper.selectOne(any())).thenReturn(disabled);

        assertBusinessError(() -> strategy.authenticate(params(USERNAME, frontHash)),
                            HttpStatus.FORBIDDEN, 1003, "您的账号已被禁用");
    }

    @Test
    @DisplayName("密码不匹配 → 401/1002，与“用户不存在”文案一致")
    void authenticate_wrongPassword_unauthorized() {
        when(userMapper.selectOne(any())).thenReturn(enabledUser(2L));
        when(authIdentityMapper.selectOne(any())).thenReturn(identityWith(storedCredential));

        String wrongHash = sha256("wrong-password");
        assertBusinessError(() -> strategy.authenticate(params(USERNAME, wrongHash)),
                            HttpStatus.UNAUTHORIZED, 1002, "用户名或密码错误");
    }

    @Test
    @DisplayName("密码为空 → 400/1001，且不查库")
    void authenticate_blankPassword_badRequest() {
        assertBusinessError(() -> strategy.authenticate(params(USERNAME, null)),
                            HttpStatus.BAD_REQUEST, 1001, "用户名或密码不能为空");

        verify(userMapper, never()).selectOne(any());
        verify(authIdentityMapper, never()).selectOne(any());
    }

    // ---------- helpers ----------

    private static SysUser enabledUser(Long userId) {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setUserCode(USERNAME);
        user.setStatus(1);
        return user;
    }

    private static SysAuthIdentity identityWith(String credential) {
        SysAuthIdentity identity = new SysAuthIdentity();
        identity.setUserId(2L);
        identity.setIdentityType("local");
        identity.setIdentifier(USERNAME);
        identity.setCredential(credential);
        return identity;
    }

    private static Map<String, String> params(String userCode, String password) {
        Map<String, String> m = new HashMap<>();
        m.put("userCode", userCode);
        m.put("password", password);
        return m;
    }

    private static void assertBusinessError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                            HttpStatus status, int code, String message) {
        assertThatThrownBy(call)
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getStatus()).isEqualTo(status);
                assertThat(ex.getCode()).isEqualTo(code);
            })
            .hasMessage(message);
    }

    private static String sha256(String raw) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
