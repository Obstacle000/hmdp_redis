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
     * 查询商铺
     * @return
     */
    Result queryById(Long id);

    /**
     * 更新
     * @param shop
     * @return
     */
    Result update(Shop shop);
}
