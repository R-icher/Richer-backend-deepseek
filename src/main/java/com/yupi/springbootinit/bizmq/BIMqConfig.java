package com.yupi.springbootinit.bizmq;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 为业务队列配置死信交换机和死信队列
 */
@Configuration
public class BIMqConfig {

    @Bean
    public DirectExchange biExchange(){
        return new DirectExchange(BIMqConstant.BI_EXCHANGE_NAME);
    }

    @Bean
    public Queue biQueue(){
        return QueueBuilder.durable(BIMqConstant.BI_QUEUE_NAME)
                // 绑定死信交换机和路由键
                .withArgument("x-dead-letter-exchange", BIMqConstant.BI_DLX_EXCHANGE_NAME)
                .withArgument("x-dead-letter-routing-key", BIMqConstant.BI_DLX_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding biBinding() {
        return BindingBuilder.bind(biQueue())
                .to(biExchange())
                .with(BIMqConstant.BI_ROUTING_KEY);
    }

    // ======= 死信队列的配置 ========
    @Bean
    public DirectExchange biDlxExchange(){
        return new DirectExchange(BIMqConstant.BI_DLX_EXCHANGE_NAME);
    }

    @Bean
    public Queue biDlxQueue(){
        return QueueBuilder.durable(BIMqConstant.BI_DLX_QUEUE_NAME).build();
    }

    @Bean
    public Binding biDlxBinding() {
        return BindingBuilder.bind(biDlxQueue())
                .to(biDlxExchange())
                .with(BIMqConstant.BI_DLX_ROUTING_KEY);
    }

}
