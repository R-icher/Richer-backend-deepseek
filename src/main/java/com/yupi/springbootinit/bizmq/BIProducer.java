package com.yupi.springbootinit.bizmq;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class BIProducer {

    @Resource
    private RabbitTemplate rabbitTemplate;

    // 指定发送到哪个交换机，路由键，消息
    public void sendMessage(String message){
        rabbitTemplate.convertAndSend(BIMqConstant.BI_EXCHANGE_NAME, BIMqConstant.BI_ROUTING_KEY, message);
    }
}
