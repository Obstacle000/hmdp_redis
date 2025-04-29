package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheUtil;
import com.hmdp.utils.RedisData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheUtil cacheUtil;

    @Override
    public Result queryById(Long id) {
        // 缓存穿透
         Shop shop = cacheUtil.queryWithPassThrough(
                 CACHE_SHOP_KEY,id,Shop.class,
                 this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        // 互斥锁 + 缓存穿透
        // Shop shop = queryWithMutex(id);

        // 逻辑过期
         /*Shop shop = cacheUtil.queryWithLogicalExpire(
                 CACHE_SHOP_KEY,id,Shop.class,
                 this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);*/
        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

   /* public Shop queryWithPassThrough(Long id){
        // 这里我们缓存的是商铺的信息,上次我们用过hash存对象,这次试试string
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);

            return shop;
        }
        // 判断空值
        if(shopJson != null) {
            // 不等于null就一定是空字符串
            log.info("redis");
            return null;
        }


        Shop shop = getById(id);
        if (shop == null) {
            // 写入空值
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "",CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;
        }

        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);



        return shop;
    }

    public Shop queryWithMutex(Long id){
        // 这里我们缓存的是商铺的信息,上次我们用过hash存对象,这次试试string
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);

            return shop;
        }
        // 判断空值
        if(shopJson != null) {
            // 不等于null就一定是空字符串
            log.info("redis");
            return null;
        }

        // 未命中,获取互斥锁
        // 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 判断成功没
            if(!isLock) {
                // 失败 - 休眠重试
                Thread.sleep(50);
                return queryWithMutex(id);// 递归
            }


            // 成功 - 数据库查询
            shop = getById(id);
            // 模拟重建延时
            Thread.sleep(200);
            if (shop == null) {
                // 写入空值
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "",CACHE_NULL_TTL, TimeUnit.MINUTES);

                return null;
            }

            // 写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放锁
            unLock(lockKey);
        }




        return shop;
    }
    // 线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id){
        // 这里我们缓存的是商铺的信息,上次我们用过hash存对象,这次试试string
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        // 没有直接返回null
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }

        // 1. 命中,先反序列化Shop
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        // 由于data是Object字段,反序列化后实际上拿到的是JSONObject类,我们需要再次反序列化
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 2. 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 过期时间在当前时间之后 - 没过期
            // 2. 1未过期 - 返回
            return shop;
        }
        // 2. 2过期 - 缓存重建
        // 3. 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);

        if(isLock) {
            // 3.1 成功 - 开启线程,执行重建
            // 使用线程池
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    // 里面去做缓存重建,调用刚才预热数据方法即可
                    this.saveShopToRedis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unLock(lockKey);
                }
            });
        }
        // 3.2 不管获取锁成功失败都返回过期信息
        return shop;
    }


    private boolean tryLock(String key) {
        // 值是什么都无所谓
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }
    private void unLock(String key) {
        // 值是什么都无所谓
        stringRedisTemplate.delete(key);
    }

    public void saveShopToRedis(Long id,Long secondsExpire) throws InterruptedException {
        // 查询数据
        Shop shop = getById(id);
        // 模拟延迟
        Thread.sleep(200);
        // 封装成逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(secondsExpire));
        // 写入redis,我们不设置过期时间,后面调用的时候手动更新
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    */
    @Override
    @Transactional()
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺不能为空");
        }

        // 1. 更新数据库
        updateById(shop);

        // 2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        // 3. 返回结果
        return Result.ok();
    }


}
