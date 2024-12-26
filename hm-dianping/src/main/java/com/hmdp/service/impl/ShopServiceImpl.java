package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private  StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Object queryById(Long id) {
//        //1.使用工具类解决缓存穿透问题
//        Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
//                id2 -> getById(id2), RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);

        //2.基于工具类使用互斥锁解决缓存击穿问题
        Shop shop = cacheClient.queryWithMutex(RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
                id2 -> getById(id2), RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

    /**
     * 解决缓存穿透的代码
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id) {
        //从redis中查询商铺缓存，命中直接返回数据
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String s = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if (!StrUtil.isBlank(s)) {
            Shop shop = JSONUtil.toBean(s, Shop.class);
            System.out.println("缓存命中");
            return shop;
        }
        //命中的是否是空值
        if (s != null) {
            return null;
        }


        //未命中则从数据库中查询数据
        Shop shop = getById(id);
        try{
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }//模拟缓存击穿的场景，因为缓存穿透需要缓存重建业务的时间较长。



        //数据不存在则返回404
        if (shop == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }

        //数据存在则写入缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);


        //返回商铺信息
        return shop;
    }

    /**
     * 解决缓存击穿的代码
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        //从redis中查询商铺缓存，命中直接返回数据
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String s = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if (!StrUtil.isBlank(s)) {
            Shop shop = JSONUtil.toBean(s, Shop.class);
            System.out.println("缓存命中");
            return shop;
        }
        //命中的是否是空值
        if (s != null) {
            return null;
        }

        //4.1实现缓存重建
        //4.2获取互斥锁
        String lockkey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean islock = trylock(lockkey);


            //4.3成功或者失败、休眠重试
            if (!islock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }


            //未命中则从数据库中查询数据
            shop = getById(id);
            Thread.sleep(200);//模拟缓存击穿的场景，因为缓存穿透需要缓存重建业务的时间较长。


            //数据不存在则返回404
            if (shop == null) {
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }

            //数据存在则写入缓存
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //4.4释放互斥锁
            unlock(lockkey);
        }

        //返回商铺信息
        return shop;
    }

    private boolean trylock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    @Override
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
