package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
/**
 * 封装缓存工具类
 */
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key , Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key , Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 解决缓存穿透的代码
     * @param id
     * @return
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallBack, Long time, TimeUnit unit) {
        //从redis中查询商铺缓存，命中直接返回数据
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if (!StrUtil.isBlank(json)) {
            return JSONUtil.toBean(json, type);

        }
        //命中的是否是空值
        if (json != null) {
            return null;
        }

        //未命中则从数据库中查询数据
        R r = dbFallBack.apply(id);
        //数据不存在则返回404
        if (r == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }

        //数据存在则写入缓存
        this.set(key, r, time, unit);


        //返回商铺信息
        return r;
    }

    /**
     * 解决缓存击穿的代码
     * @param id
     * @return
     */
    public <R,ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallBack, Long time, TimeUnit unit) {
        //从redis中查询商铺缓存，命中直接返回数据
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if (!StrUtil.isBlank(json)) {
            R r = JSONUtil.toBean(json, type);
            return r;
        }
        //命中的是否是空值
        if (json != null) {
            return null;
        }

        //4.1实现缓存重建
        //4.2获取互斥锁
        String lockkey = "lock:shop:" + id;
        R r = null;
        try {
            boolean islock = tryLock(lockkey);


            //4.3成功或者失败、休眠重试
            if (!islock) {
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallBack, time, unit);
            }


            //未命中则从数据库中查询数据
            r = dbFallBack.apply(id);
            Thread.sleep(200);//模拟缓存击穿的场景，因为缓存穿透需要缓存重建业务的时间较长。


            //数据不存在则返回404
            if (r == null) {
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }

            //数据存在则写入缓存
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //4.4释放互斥锁
            unlock(lockkey);
        }

        //返回商铺信息
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

}
