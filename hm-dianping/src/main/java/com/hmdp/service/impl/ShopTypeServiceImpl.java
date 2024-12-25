package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private  StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> queryTypeList() {
        //查询缓存，缓存存在则直接返回
        String key = RedisConstants.CACHE_SHOPTYPE_KEY;
        String s = stringRedisTemplate.opsForValue().get(key);
        if (!StrUtil.isBlank(s)){
            List<ShopType> lists = JSONUtil.toList(s, ShopType.class);
            System.out.println("商铺分类缓存命中");
            return lists;
        }

        //缓存不存在，查询数据库
        List<ShopType> lists = query().orderByAsc("sort").list();

        //数据库存在，写入缓存
        if (lists != null) {
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(lists));
        }

        //数据库不存在，返回即可
        //返回数据
        return lists;
    }
}
