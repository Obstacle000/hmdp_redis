package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.View;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisidWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    // 脚本类初始化
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        // resource目录就在ClassPath下,直接写文件名即可
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    // 创建独立线程,异步下单,完成任务
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct // 代表当前类初始化后执行
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {

        // 这个任务应该在队列里放东西之前执行,不然有延迟
        // 通过spring提供的注解实现,项目一启动就执行任务
        private final String queueName = "stream.orders";
        private volatile boolean running = true; // 控制线程停止

        public void stop() {
            running = false;
        }

        @Override
        public void run() {
            while (running) {
                try {
                    // 获取订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 判断集合拿没拿到
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    // 计解析消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 创建订单
                    handleVoucherOrder(voucherOrder);
                    // ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常，准备处理 pending-list...", e);
                    try {
                        handlePendingList(); // pending 里也有异常控制
                    } catch (Exception ex) {
                        log.error("处理 pending-list 异常", ex);
                    }

                    try {
                        TimeUnit.SECONDS.sleep(1); // 防止一直报错刷屏
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        private void handlePendingList() {
            while (running) {
                try {
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );

                    if (list == null || list.isEmpty()) {
                        break; // 没有未确认消息了
                    }

                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {
                    log.error("处理 pending 消息异常", e);
                    break; // 防止一直报错刷屏
                }
            }
        }
    }
        /*private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<VoucherOrder>(1024*1024);

        private class VoucherOrderHandler implements Runnable {
            // 这个任务应该在队列里放东西之前执行,不然有延迟
            // 通过spring提供的注解实现,项目一启动就执行任务
            @Override
            public void run() {
              while (true) {
                  try {
                      // 获取订单信息
                      VoucherOrder voucherOrder = orderTasks.take();
                      // 创建订单
                      handleVoucherOrder(voucherOrder);
                  } catch (InterruptedException e) {
                      log.error("处理订单异常",e);
                  }

              }
            }
        }*/ // 原始开启线程完成任务方法
        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            // 这里由于开启了新的线程ThreadLocal取不到userId
            Long userId = voucherOrder.getUserId();
            // 创建锁对象
            RLock lock = redissonClient.getLock("lock:order:" + userId);
            boolean isLock = lock.tryLock();
            ;
            if (!isLock) {
                // 其实这里不加锁也行,redis已经处理了并发问题,做一个兜底吧
                log.error("不允许重复下单");
                return;
            }
            try {
                // 这里由于spring也是通过ThreadLocal获取代理对象,拿不到
                // 所以我们只能提前获取代理对象了
                // IVoucherOrderService proxy =(IVoucherOrderService) AopContext.currentProxy();

                proxy.createVoucherOrder(voucherOrder);
            } catch (IllegalStateException e) {
                throw new RuntimeException(e);
            } finally {
                lock.unlock();
            }
        }

        // 后续得拿到定义的代理对象,调用事务方法
        private IVoucherOrderService proxy;

        @Override
        public Result seckillVoucher(Long voucherId) {
            // 1. 执行lua
            Long userId = UserHolder.getUser().getId();

            // 订单id(idWorker)
            long orderId = redisidWorker.nextId("order");

            // 判断资格 + 发送到消息队列
            Long result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(), userId.toString(), String.valueOf(orderId)
            );
            // 2. 判断是否为0
            int r = result.intValue();
            if (r != 0) {
                // 2.1 不为0 - 没有购买资格
                return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
            }

            // 获取代理对象
            proxy = (IVoucherOrderService) AopContext.currentProxy();

            // 返回订单id
            return Result.ok(orderId);

        }
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 执行lua
        Long userId = UserHolder.getUser().getId();
        // 判断资格
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId
        );
        // 2. 判断是否为0
        int r = result.intValue();
        if (r != 0) {
            // 2.1 不为0 - 没有购买资格
            return Result.fail(r == 1?"库存不足":"不能重复下单");
        }

        // 2.2 为0 - 有资格,保存到队列
        // 订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 订单id(idWorker)
        long orderId = redisidWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 用户id
        voucherOrder.setUserId(userId);
        // 代金券id
        voucherOrder.setVoucherId(voucherId);
        // 放到队列
        orderTasks.add(voucherOrder);
        // 获取代理对象
        proxy =(IVoucherOrderService) AopContext.currentProxy();

        // 返回订单id
        return Result.ok(orderId);

    }*/ // 原始发送消息到阻塞队列

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // 判断时间
        LocalDateTime beginTime = voucher.getBeginTime();
        if (beginTime.isAfter(LocalDateTime.now())) {
            // 没有开始
            return Result.fail("活动尚未开始");
        }
        LocalDateTime endTime = voucher.getEndTime();
        if (endTime.isBefore(LocalDateTime.now())) {
            // 结束
            return Result.fail("活动已结束");
        }

        // 库存
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        Long id = UserHolder.getUser().getId();
        // 细节toString底层会new String字符串,导致每一次调用创建不同的对象,锁不唯一
        // 这个intern()作用是去串池找和字符串值一样的地址引用返回给你,确保锁唯一

        // 创建锁对象
        // SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + id);
        RLock lock = redissonClient.getLock("lock:order:" + id);
        boolean isLock = lock.tryLock();;
        if (!isLock) {
            // 这里根据业务逻辑执行操作
            // 没拿到锁的原因是,一个用户尝试着并发的去抢购
            return Result.fail("一人只允许下一单");
        }
        try {
            IVoucherOrderService proxy =(IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }*/ // 原始扣库存,判断一人一单代码

        @Transactional
        public void createVoucherOrder(VoucherOrder voucherOrder) {

            Long voucherId = voucherOrder.getVoucherId();
            // 一人一单
            Long userId = voucherOrder.getUserId();

            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();

            if (count > 0) {
                log.error("用户购买过");
                return;
            }

            // 扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // 这里肯定是先where后set的
                    .eq("voucher_id", voucherId).gt("stock", 0)
                    .update();
            if (!success) {
                log.error("库存不足");
                return;
            }

            save(voucherOrder);

        }


    }
