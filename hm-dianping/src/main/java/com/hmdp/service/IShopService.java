package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    /**
     * 根据id查询商品信息，实现缓存功能
     * @param id
     * @return
     */
    Object queryById(Long id);

    /**
     * 更新商铺数据
     * @param shop
     * @return
     */
    Result updateShop(Shop shop);
}
