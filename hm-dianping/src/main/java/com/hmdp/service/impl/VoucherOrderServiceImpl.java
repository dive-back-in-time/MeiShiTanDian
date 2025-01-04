package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 重点！！！！！！
 * 结合GPT再熟悉下流程！！！！！
 * 用户发起秒杀请求，调用 seckillVoucher 方法。
 * Lua 脚本验证秒杀资格，合法订单信息存入阻塞队列。
 * 异步线程从阻塞队列取出订单，调用 handleVoucherOrder 方法处理。
 * 使用分布式锁和乐观锁生成订单并扣减库存
 */

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    private IVoucherOrderService proxy;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private final static ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //获取消息队列中的信息
    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";

        @Override
        public void run() {
            while(true) {
                try {
                    //获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 count 1 BLOCK 2000 STREAMS streams.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        continue;
                    }

                    //解析消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    //创建订单
                    handleVoucherOrder(voucherOrder);

                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        /**
         * 没有经过ACK，需要重试
         */
        private void handlePendingList() {
            while(true) {
                try {
                    //获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 count 1  STREAMS streams.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //判断消息获取是否成功
                    //pendingList没有异常消息，读取成功
                    if (list == null || list.isEmpty()) {
                        break;
                    }

                    //解析消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    //创建订单
                    handleVoucherOrder(voucherOrder);

                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

//    private class VoucherOrderHandler implements Runnable{
//        @Override
//        public void run() {
//            while(true) {
//                try {
//                    VoucherOrder order = orderTasks.take();
//                    log.info("订单已从阻塞队列中取出，订单信息：{}", order);
//                    //创建订单
//                    handleVoucherOrder(order);
//
//                } catch (InterruptedException e) {
//                    log.error("处理订单异常", e);
//                }
//            }
//        }
//    }

    private void handleVoucherOrder(VoucherOrder order) {
        Long userId = order.getUserId();
        //创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);

        /**
         * 这里其实可以不需要加锁，因为加锁本质是防止用户重复下单
         * 但lua脚本已经排除了用户重复下单的可能性
         * 而且lua脚本操作的原子性更强
         */
        //获取锁
        boolean islock = lock.tryLock(1200);
        if (!islock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            log.info("开始创建订单");
            proxy.getVoucherResult(order);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    //没有实现异步秒杀
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//        //判断秒杀是否开始
//        LocalDateTime beginTime = voucher.getBeginTime();
//        if (beginTime.isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//
//        //判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束");
//        }
//
//        //判断库存是否充足
//        int sum = voucher.getStock();
//        if (sum < 1) {
//            return Result.fail("库存不足");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//
//        //创建锁对象
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        //获取锁
//        boolean islock = lock.tryLock(1200);
//        if (!islock) {
//            return Result.fail("不允许重复下单");
//        }
//
//
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.getVoucherResult(voucherId);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
//            lock.unlock();
//        }
//
//
//    }

    //实现异步秒杀
    //基于stream消息队列实现
    @Override
    public Result seckillVoucher(Long voucherId) {
        long orderId = redisIdWorker.nextId("order");

        //执行脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList()
                , voucherId.toString()
                , UserHolder.getUser().getId().toString()
                , String.valueOf(orderId));
        //判断结果是否为0，有购买资格需要将订单信息保存到阻塞队列
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        log.info("代理对象类型：{}", proxy.getClass().getName());

        //返回订单id
        return Result.ok(0);

    }

//    //实现异步秒杀
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //执行脚本
//        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), UserHolder.getUser().getId().toString());
//        //判断结果是否为0，有购买资格需要将订单信息保存到阻塞队列
//        int r = result.intValue();
//        if (r != 0) {
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//        long orderId = redisIdWorker.nextId("order");
//
//        // TODO 保存阻塞队列
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long longId = redisIdWorker.nextId("order");
//        voucherOrder.setId(longId);//订单id
//        voucherOrder.setUserId(UserHolder.getUser().getId());
//        voucherOrder.setVoucherId(voucherId);
//        orderTasks.add(voucherOrder);
//        log.info("订单已添加到阻塞队列，订单信息：{}", voucherOrder);
//
//
//        //获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        log.info("代理对象类型：{}", proxy.getClass().getName());
//
//        //返回订单id
//        return Result.ok(0);
//
//    }

    @Transactional
    public void getVoucherResult(VoucherOrder order) {
        log.info("正在处理订单，订单信息：{}", order);

        //实现一人一单，判断用户id,优惠券id是否已在订单中存在
        Long userId = order.getUserId();


        int count = query().eq("user_id", userId).eq("voucher_id", order.getVoucherId()).count();
        if (count > 0) {
            log.error("用户已购买过一次");
            return;
        }

        //扣库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", order.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("创建失败，库存不足");
            return;
        }

        save(order);


        }


}
