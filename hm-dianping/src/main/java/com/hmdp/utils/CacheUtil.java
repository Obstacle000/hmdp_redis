package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
@RequiredArgsConstructor
public class CacheUtil {
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * ttl
     * @param key
     * @param value
     * @param time
     * @param timeUnit
     */
    public void set(String key, Object value, Long time, TimeUnit timeUnit) {

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,timeUnit);
    }

    /**
     * 逻辑过期
     * @param key
     * @param value
     */
    public void setWithLogicalExpire(String key, Object value,Long time, TimeUnit timeUnit) {
        // 封装
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(timeUnit.toSeconds(time))));

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 对一般数据的缓存穿透问题解决
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param timeUnit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R,ID> R queryWithPassThrough(
            String keyPrefix,
            ID id, Class<R> type,
            Function<ID,R> dbFallback,
            Long time, TimeUnit timeUnit) {
        // 这个type就是,你之前用过,方法里面传递(类.class),然后返回对应的类
        String key = keyPrefix + id;
        // 这里我们缓存的是商铺的信息,上次我们用过hash存对象,这次试试string
        String json = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 判断空值
        if(json != null) {
            // 不等于null就一定是空字符串
            return null;
        }
        // 用户传递一个函数式编程
        R r = dbFallback.apply(id);
        if (r == null) {
            // 写入空值
            stringRedisTemplate.opsForValue().set(key, "",CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;
        }

        this.set(key,r,time,timeUnit);


        return r;
    }

    // 线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 对热点数据(预热完)的数据的缓存击穿解决
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param timeUnit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R,ID> R queryWithLogicalExpire(
            String keyPrefix,
            ID id, Class<R> type,
            Function<ID,R> dbFallback,
            Long time,
            TimeUnit timeUnit){
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 没有直接返回null
        if (StrUtil.isBlank(json)) {
            return null;
        }

        // 1. 命中,先反序列化json
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        // 由于data是Object字段,反序列化后实际上拿到的是JSONObject类,我们需要再次反序列化
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 2. 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 过期时间在当前时间之后 - 没过期
            // 2. 1未过期 - 返回
            return r;
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
                    // 查数据库
                    R result = dbFallback.apply(id);
                    // 写入redis
                    setWithLogicalExpire(key,result,time,timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unLock(lockKey);
                }
            });
        }
        // 3.2 不管获取锁成功失败都返回过期信息
        return r;
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

}
