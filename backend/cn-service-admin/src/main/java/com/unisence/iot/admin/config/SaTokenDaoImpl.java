package com.unisence.iot.admin.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.session.SaSession;
import com.unisence.iot.admin.auth.config.AuthConfigProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class SaTokenDaoImpl implements SaTokenDao {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisTemplate<String, Object> objectRedisTemplate;
    private final String keyPrefix;

    public SaTokenDaoImpl(StringRedisTemplate stringRedisTemplate,
                          @Qualifier("saRedisTemplate") RedisTemplate<String, Object> objectRedisTemplate,
                          AuthConfigProperties authConfigProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectRedisTemplate = objectRedisTemplate;
        this.keyPrefix = authConfigProperties.getRedisKeyPrefix();
    }

    private String k(String key) {
        return keyPrefix + key;
    }

    // ============================= String =============================

    @Override
    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(k(key));
    }

    @Override
    public void set(String key, String value, long timeout) {
        if (timeout == SaTokenDao.NEVER_EXPIRE) {
            stringRedisTemplate.opsForValue().set(k(key), value);
        } else {
            stringRedisTemplate.opsForValue().set(k(key), value, timeout, TimeUnit.SECONDS);
        }
    }

    @Override
    public void update(String key, String value) {
        long expire = getTimeout(key);
        if (expire == SaTokenDao.NOT_VALUE_EXPIRE) return;
        set(key, value, expire);
    }

    @Override
    public void delete(String key) {
        stringRedisTemplate.delete(k(key));
    }

    @Override
    public long getTimeout(String key) {
        Long expire = stringRedisTemplate.getExpire(k(key), TimeUnit.SECONDS);
        return expire == null ? SaTokenDao.NOT_VALUE_EXPIRE : expire;
    }

    @Override
    public void updateTimeout(String key, long timeout) {
        if (timeout == SaTokenDao.NEVER_EXPIRE) {
            stringRedisTemplate.persist(k(key));
        } else {
            stringRedisTemplate.expire(k(key), timeout, TimeUnit.SECONDS);
        }
    }

    // ============================= Object =============================

    @Override
    public Object getObject(String key) {
        return objectRedisTemplate.opsForValue().get(k(key));
    }

    @Override
    public <T> T getObject(String key, Class<T> clazz) {
        return clazz.cast(getObject(key));
    }

    @Override
    public void setObject(String key, Object object, long timeout) {
        if (timeout == SaTokenDao.NEVER_EXPIRE) {
            objectRedisTemplate.opsForValue().set(k(key), object);
        } else {
            objectRedisTemplate.opsForValue().set(k(key), object, timeout, TimeUnit.SECONDS);
        }
    }

    @Override
    public void updateObject(String key, Object object) {
        long expire = getObjectTimeout(key);
        if (expire == SaTokenDao.NOT_VALUE_EXPIRE) return;
        setObject(key, object, expire);
    }

    @Override
    public void deleteObject(String key) {
        objectRedisTemplate.delete(k(key));
    }

    @Override
    public long getObjectTimeout(String key) {
        Long expire = objectRedisTemplate.getExpire(k(key), TimeUnit.SECONDS);
        return expire == null ? SaTokenDao.NOT_VALUE_EXPIRE : expire;
    }

    @Override
    public void updateObjectTimeout(String key, long timeout) {
        if (timeout == SaTokenDao.NEVER_EXPIRE) {
            objectRedisTemplate.persist(k(key));
        } else {
            objectRedisTemplate.expire(k(key), timeout, TimeUnit.SECONDS);
        }
    }

    // ============================= Session =============================

    @Override
    public SaSession getSession(String sessionId) {
        return (SaSession) getObject(sessionId);
    }

    @Override
    public void setSession(SaSession session, long timeout) {
        setObject(session.getId(), session, timeout);
    }

    @Override
    public void updateSession(SaSession session) {
        updateObject(session.getId(), session);
    }

    @Override
    public void deleteSession(String sessionId) {
        deleteObject(sessionId);
    }

    @Override
    public long getSessionTimeout(String sessionId) {
        return getObjectTimeout(sessionId);
    }

    @Override
    public void updateSessionTimeout(String sessionId, long timeout) {
        updateObjectTimeout(sessionId, timeout);
    }

    // ============================= Search =============================

    @Override
    public List<String> searchData(String prefix, String keyword, int start, int size, boolean sortType) {
        Set<String> keys = stringRedisTemplate.keys(keyPrefix + prefix + "*" + keyword + "*");
        if (keys == null || keys.isEmpty()) return Collections.emptyList();
        // strip keyPrefix so callers receive the original Sa-Token key format
        List<String> list = new ArrayList<>();
        for (String k : keys) {
            list.add(k.startsWith(keyPrefix) ? k.substring(keyPrefix.length()) : k);
        }
        if (sortType) Collections.sort(list);
        int from = Math.max(0, start);
        if (from >= list.size()) return Collections.emptyList();
        int to = size < 0 ? list.size() : Math.min(list.size(), from + size);
        return list.subList(from, to);
    }
}
