package com.hmdp.utils;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class SimpleRedisLock implements ILock {

    private final StringRedisTemplate stringRedisTemplate;
    // 这里不能把锁的名字给写死,因为这个是分布式锁,很多业务都得用
    private final String name;
    private static final String KEY_PREFIX = "lock:";

    @Override
    public boolean tryLock(long timeoutSec) {
        // 这个value最好能表示每一个线程
        long id = Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, id+"", timeoutSec, TimeUnit.SECONDS);
        // Boolean->boolean自动拆箱的时候如果success是null,就空指针了
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}
