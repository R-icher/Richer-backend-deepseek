package com.yupi.springbootinit.bizmq;

public interface BIMqConstant {
    String BI_EXCHANGE_NAME = "bi_exchange";

    String BI_QUEUE_NAME = "bi_queue";

    String BI_ROUTING_KEY = "bi_routingKey";

    // 新增死信队列常量
    String BI_DLX_EXCHANGE_NAME = "bi_dlx_exchange";
    String BI_DLX_ROUTING_KEY = "bi_dlx_routingKey";
    String BI_DLX_QUEUE_NAME = "bi_dlx_queue";
}
