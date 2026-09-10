package com.unisence.iot.admin.system.service;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.unisence.iot.admin.system.dto.UserOnlineQuery;
import com.unisence.iot.admin.system.vo.SysUserOnlineVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 在线用户 Service 业务层处理（数据源：Sa-Token 托管的 Redis 活跃会话）
 */
@Service
public class SysUserOnlineServiceImpl implements SysUserOnlineService {

    @Override
    public PageResult<SysUserOnlineVO> listOnlineUsers(PageRequest<UserOnlineQuery> request) {
        UserOnlineQuery query = request.getQuery();
        String userCode = query != null ? query.getUserCode() : null;
        String ipaddr = query != null ? query.getIpaddr() : null;

        // searchTokenValue 返回 Redis Key（如 Authorization:login:token:<uuid>），统一剥离为原始 token
        String tokenKeyPrefix = StpUtil.stpLogic.splicingKeyTokenValue("");
        List<String> tokenKeys = StpUtil.searchTokenValue("", 0, -1, false);

        List<SysUserOnlineVO> onlineUsers = new ArrayList<>();
        for (String key : tokenKeys) {
            String tokenValue = key.startsWith(tokenKeyPrefix) ? key.substring(tokenKeyPrefix.length()) : key;

            // isCreate=false：会话已失效则返回 null，直接跳过
            SaSession session = StpUtil.stpLogic.getTokenSessionByToken(tokenValue, false);
            if (session == null) {
                continue;
            }

            String loginUserCode = session.getString(SESSION_KEY_USER_CODE);
            String loginIp = session.getString(SESSION_KEY_IP);

            // 契约 §1.5：userCode 模糊匹配，ipaddr 精确匹配
            if (StringUtils.hasText(userCode) && (loginUserCode == null || !loginUserCode.contains(userCode))) {
                continue;
            }
            if (StringUtils.hasText(ipaddr) && !ipaddr.equals(loginIp)) {
                continue;
            }

            SysUserOnlineVO vo = new SysUserOnlineVO();
            vo.setTokenId(tokenValue);
            vo.setUserCode(loginUserCode);
            vo.setUserName(session.getString(SESSION_KEY_USER_NAME));
            vo.setIpaddr(loginIp);
            vo.setLoginLocation(session.getString(SESSION_KEY_LOCATION));
            vo.setBrowser(session.getString(SESSION_KEY_BROWSER));
            vo.setOs(session.getString(SESSION_KEY_OS));
            vo.setLoginTime(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(session.getCreateTime()), ZoneId.systemDefault()));
            onlineUsers.add(vo);
        }

        // 数据源为 Redis 全量会话，在内存中完成分页
        int pageNum = request.getPageNum();
        int pageSize = request.getPageSize();
        List<SysUserOnlineVO> pagedList = onlineUsers.stream()
            .skip((long) (pageNum - 1) * pageSize)
            .limit(pageSize)
            .toList();

        return new PageResult<>(pagedList, onlineUsers.size());
    }

    @Override
    public void kickout(String tokenId) {
        StpUtil.logoutByTokenValue(tokenId);
    }
}
