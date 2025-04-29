package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;


import java.util.Collections;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class SimpleRedisLock implements ILock {

    private final StringRedisTemplate stringRedisTemplate;
    // 这里不能把锁的名字给写死,因为这个是分布式锁,很多业务都得用
    private final String name;
    private static final String KEY_PREFIX = "lock:";
    // 拼接在id的前面
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";

    // 脚本类初始化
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        // resource目录就在ClassPath下,直接写文件名即可
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 这个value最好能表示每一个线程
        String id = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, id, timeoutSec, TimeUnit.SECONDS);
        // Boolean->boolean自动拆箱的时候如果success是null,就空指针了
        return Boolean.TRUE.equals(success);
    }
    @Override
    public void unlock() {
        // 调用脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }

    /*@Override
    public void unlock() {
        // 判断线程标识是否一致
        String id = ID_PREFIX + Thread.currentThread().getId();
        String comparedId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if(id.equals(comparedId)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }*/
}
