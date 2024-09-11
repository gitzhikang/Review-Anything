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
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.print.DocFlavor;
import java.beans.Transient;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result queryById(Long shopId) {
        //缓存穿透
//        Shop shop = queryWithPassThrough(shopId);
        //互斥锁解决缓存击穿
        Shop shop = queryWithLogicalExpire(shopId);
        if (shop == null) {
            return Result.fail("数据不存在");
        }
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long shopId){
        //****不需要关注缓存击穿，因为如果在缓存中没有存在的条目直接返回空***,所有的热点key会提前存入，并设置逻辑过期时间
        String key = RedisConstants.CACHE_SHOP_KEY+shopId;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //shopJson is not null
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        //如果命中，反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject)redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime localDateTime = redisData.getExpireTime();
        //判断是否过期
        if (localDateTime.isAfter(LocalDateTime.now())) {
            //未过期，则返回
            return shop;
        }

        //已经过期，需要缓存重建
        //缓存重建

        //1.获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY+shopId;
        //2.判断是否获取锁成功
        boolean isLock = tryLock(lockKey);
        //3.成功开启独立线程,重建缓存
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.saveShopToRedis(shopId,30L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unLock(lockKey);
                }

            });
        }
        //4.失败直接返回
        return shop;
    }

    public Shop queryWithPassThrough(Long shopId){
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + shopId);
        //shopJson is not null
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中是空值
        if (shopJson!=null) {
            //不是空，一定是空值
            return null;
        }
        //什么都没命中，是null->查询数据库
        Shop shop = getById(shopId);
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + shopId, "",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + shopId, JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    public Shop queryWithMutex(Long shopId){
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + shopId);
        //shopJson is not null
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中是空值
        if (shopJson!=null) {
            //不是空，说明有错误
            return null;
        }
        //一定是空值
        //什么都没命中，是null->查询数据库，缓存重建
        //1.获取互斥锁
        String lockKey = "lock:shop" + shopId;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //2.判断是否成功
            if (!isLock) {
                //3.失败则休眠重新获取缓存

                Thread.sleep(50);
                return queryWithMutex(shopId);
            }
            //4.获取成功，查询数据库
            shop = getById(shopId);
            Thread.sleep(200);
            //如果数据库没有，缓存空值，防止缓存穿透
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + shopId, "",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //存在，写入redis
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + shopId, JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放锁
            unLock(lockKey);
        }
        return shop;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShopToRedis(Long id, Long expiredSeconds) throws InterruptedException {
        Shop shop = getById(id);
        Thread.sleep(200);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expiredSeconds));
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("invalid shop ID");
        }
        //query db
        updateById(shop);
        //delete cache
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
