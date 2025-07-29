package com.yupi.springbootinit.monitor;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadPoolExecutor;

public class AiQueueMonitor {
    private final ThreadPoolExecutor executor;

    public AiQueueMonitor(ThreadPoolExecutor executor) {
        this.executor = executor;
    }

    /**
     * @return 当前线程池队列中待执行任务的数量
     */
    public int getCurrentQueueSize() {
        return executor.getQueue().size();
    }
}
