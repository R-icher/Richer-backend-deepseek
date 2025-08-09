package com.yupi.springbootinit.config;

import com.yupi.springbootinit.monitor.AiQueueMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadPoolExecutor;

@Component
@Slf4j
@EnableScheduling
public class DynamicThreadPoolScaler {
    // 注入线程池
    private final ThreadPoolExecutor threadPoolExecutor;

    // 注入任务控制器，监视任务队列的长度
    private final AiQueueMonitor aiQueueMonitor;

    // 配置阈值:当队列长度超过 highThreshold 时，扩容；低于 lowThreshold 时，缩容
    private final int minCore = 2;
    private final int maxCore = 10;
    private final int highThreshold = 8;
    private final int lowThreshold = 5;

    public DynamicThreadPoolScaler(ThreadPoolExecutor threadPoolExecutor, AiQueueMonitor aiQueueMonitor) {
        this.threadPoolExecutor = threadPoolExecutor;
        this.aiQueueMonitor = aiQueueMonitor;
    }

    // 每五秒扫描一次队列
    @Scheduled(fixedDelay = 5_000)
    public void scalePool(){
        int queueSize = aiQueueMonitor.getCurrentQueueSize();
        int currentCore = threadPoolExecutor.getCorePoolSize();
        int newCore = currentCore;

        if(queueSize > highThreshold && currentCore < maxCore){
            // 压力变大，将核心线程数扩大
            newCore = Math.min(maxCore, currentCore + 1);
        }else if(queueSize < lowThreshold && currentCore > minCore){
            // 压力变小，减少核心线程数
            newCore = Math.max(minCore, currentCore - 1);
        }

        if(newCore != currentCore){
            threadPoolExecutor.setCorePoolSize(newCore);  // 更新核心线程数
            log.info("调整线程池核心线程数：{} → {}（队列长度={}）",
                    currentCore, newCore, queueSize);
        }
    }
}
