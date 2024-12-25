package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

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

    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Object queryById(Long id) {
        //从redis中查询商铺缓存，命中直接返回数据
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String s = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if (!StrUtil.isBlank(s)) {
            Shop shop = JSONUtil.toBean(s, Shop.class);
            System.out.println("缓存命中");
            return Result.ok(shop);
        }


        //未命中则从数据库中查询数据
        Shop shop = getById(id);


        //数据不存在则返回404
        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        //数据存在则写入缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));

        //返回商铺信息
        return Result.ok(shop);
    }
}
