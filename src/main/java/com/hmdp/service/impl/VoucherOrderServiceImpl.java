package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
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
    private RedisIdWorker redisIdWorker;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("did't start");
        }
        //3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("has ended");
        }
        //4.判断库存是否充足
        if(voucher.getStock()<1){
            return Result.fail("stock sold out");
        }
        Long userId = UserHolder.getUser().getId();
        //保证事务提交后释放锁
        synchronized (userId.toString().intern()) {
            //spring需要使用代理对象实现事务
            //获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }


    //这里synchronized不建议直接加在function上，因为此时的作用域是this对象，而Spring的容器中只会存在一个service对象，
    //那么，如果后面有其他用户访问的话，都会被锁，
    //而这里我们的目的是：根据userId上锁，保证同一个用户多次访问的话会受到锁的限制
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("user has bought");
        }
        //5.扣件库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherId).gt("stock",0)
                .update();
        if (!success) {
            return Result.fail("stock sold out");
        }
        //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //6.1 order id
        Long id = redisIdWorker.netId("order");
        voucherOrder.setId(id);
        //6.2 user id
//            Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        //6.3 voucher id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(id);


    }
}
