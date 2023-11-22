package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    //注入id生成器
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始!");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getBeginTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束!");
        }
        // 4.判断库存是否充足
        if(voucher.getStock()<1){
            return Result.fail("库存不足！");
        }
        Long userId = UserHolder.getUser().getId();
        //基于Redis的分布式锁
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        boolean isLock = lock.tryLock(5);
        if(!isLock){
            // 获取锁失败,返回错误或重试
            return Result.fail("不允许重复下单");
        }
        try {
            //获取代理对象（事务）
            IVoucherOrderService proxy =(IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }

        /**
         * //注意：synchronized在集群模式下锁不住，仅限单JVM。锁是基于JVM锁监视器的,同一id对应同一锁
         *         //在外面上锁，确保在事务提交后再释放锁
         *         synchronized (userId.toString().intern()){
         *             //this目标没有事务功能 spring事务失效。
         * //            return this.createVoucherOrder(voucherId);
         *             //需要拿到事务动态代理对象
         *             IVoucherOrderService proxy =(IVoucherOrderService) AopContext.currentProxy();
         *             return proxy.createVoucherOrder(voucherId);
         *         }
         */

    }

    @Transactional
    public Result createVoucherOrder(Long voucherId){

        //用户id
        Long userId = UserHolder.getUser().getId();

        //synchronized不要加在方法上，应该是一个用户一把锁
        //.intern()确保同一用户toString（）得到的string相同
//        synchronized (userId.toString().intern()){
            //5. 一人一单限制
            //5.1 查询订单
            Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            //5.2 判断是否存在
            if(count > 0){
                return Result.fail("用户已经购买过一次！");
            }

            // 5.扣减库存
            boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId)
                    .gt("stock",0)//乐观锁CAS解决线程并发问题
                    .update();
            if(!success){
                //扣减失败，库存不足
                return Result.fail("库存不足！");
            }
            // 6.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            //用户id
            voucherOrder.setUserId(userId);
            //代金券id
            voucherOrder.setVoucherId(voucherId);

            save(voucherOrder);
            // 7.返回订单id
            return Result.ok(orderId);
//        }
        //还有个问题：锁释放，但事务尚未提交。依然存在并发安全问题。
        // 应该在事务提交后再释放锁
    }
}
