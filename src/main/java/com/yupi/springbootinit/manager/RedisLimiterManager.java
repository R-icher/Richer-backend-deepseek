package com.yupi.springbootinit.manager;

import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;

@Service
public class RedisLimiterManager {

    @Resource
    private RedissonClient redissonClient;

    /**
     * 限流操作
     */
    public void doPateLimit(String key){
        // 创建限流器，key 为每个用户唯一的id
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
        // 配置限流器，设置为每秒两次请求（即往令牌桶内每秒放入两个令牌）
        rateLimiter.trySetRate(RateType.OVERALL, 2, Duration.ofSeconds(1));

        // 来一次操作，就请求一次令牌
        /**
         * 这里设置一次性拿多少个令牌，可以设置 `vip` 用户和普通用户，根据桶内令牌的数量，用户去拿取令牌，
         * `vip`用户可以一次性请求1个令牌【这样流量就会更多，可以申请更多次key】。
         * 普通用户一次性请求5个令牌【这样从桶中获取令牌的时候就只能拿几次就被限流了】
         */
        boolean canOp = rateLimiter.tryAcquire(1);
        // 如果没有拿到令牌还想执行操作就抛出异常
        if(!canOp){
            throw new BusinessException(ErrorCode.TOO_MANY_REQUEST);
        }
    }
}
