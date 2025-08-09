package com.yupi.springbootinit.bizmq;

public interface BIMqConstant {
    String BI_EXCHANGE_NAME = "bi_exchange";

    String BI_QUEUE_NAME = "bi_queue";

    String BI_ROUTING_KEY = "bi_routingKey";

    // 死信队列
    String BI_DLX_EXCHANGE_NAME = "bi.dlx.exchange";
    String BI_DLX_ROUTING_KEY = "bi.dlx.routingKey";
    String BI_DLX_QUEUE_NAME = "bi.dlx.queue";
}
