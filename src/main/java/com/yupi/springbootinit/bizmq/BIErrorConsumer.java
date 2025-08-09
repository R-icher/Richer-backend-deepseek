package com.yupi.springbootinit.bizmq;

import com.rabbitmq.client.Channel;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.service.ChartService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;

@Component
@Slf4j
public class BIErrorConsumer {

    @Resource
    private ChartService chartService;

    @RabbitListener(queues = {BIMqConstant.BI_DLX_QUEUE_NAME}, ackMode = "MANUAL")
    public void receiveErrorMessage(String message, Channel channel,
                                    @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            long chartId = Long.parseLong(message);

            // 更新数据库信息
            Chart updateChart = new Chart();
            updateChart.setId(chartId);
            updateChart.setStatus("failed");
            updateChart.setExecMessage("任务进入死信队列，生成失败");
            chartService.updateById(updateChart);

            log.warn("死信队列消息处理完成，chartId={} 标记为 failed", chartId);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("死信队列消息处理失败", e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}

