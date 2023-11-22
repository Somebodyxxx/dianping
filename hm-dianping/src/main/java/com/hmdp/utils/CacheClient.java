package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    private StringRedisTemplate stringRedisTemplate;

    public  CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key,Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key,Object value, Long time, TimeUnit unit){
        // 设置逻辑过期,用于解决缓存击穿问题
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 解决缓存穿透问题： 缓存null值
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id,
                                         Class<R> type,
                                         Function<ID,R> dbFallBack,
                                         Long time,TimeUnit unit){
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if(StrUtil.isNotBlank(json)){// null / “”时 该方法都返回false
            //反序列化
            // 3.存在 直接返回
            return JSONUtil.toBean(json, type);
        }
        // 解决缓存穿透问题： 缓存null值
        if (json != null){
            //返回一个错误信息
            return null;
        }
        // 4.不存在，根据id查询数据库
        R r = dbFallBack.apply(id);
        // 5. 不存在，直接返回错误
        if(r == null){
            // 解决缓存穿透问题： 将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6. 存在 ，写入redis，再返回
        this.set(key,r,time,unit);
        return r;
    }
}
