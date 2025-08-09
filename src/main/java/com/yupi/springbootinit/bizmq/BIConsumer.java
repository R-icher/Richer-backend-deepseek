package com.yupi.springbootinit.bizmq;

import com.rabbitmq.client.Channel;
import com.yupi.springbootinit.api.AIManager;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.service.ChartService;
import com.yupi.springbootinit.utils.ExcelUtils;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
@Slf4j
public class BIConsumer {

    @Resource
    private ChartService chartService;

    @Resource
    private AIManager aiManager;

    // 指定接收类型为人工接收
    @SneakyThrows
    @RabbitListener(queues = {BIMqConstant.BI_QUEUE_NAME}, ackMode = "MANUAL")
    public void receiveMessage(String message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag){

        if(StringUtils.isBlank(message)){
            channel.basicNack(deliveryTag, false, false);  // 抛异常了就拒绝掉这条消息，并且不用放入到消息队列中
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "消息为空");
        }
        long chartId = Long.parseLong(message);
        Chart chart = chartService.getById(chartId);
        if(chart == null){
            channel.basicNack(deliveryTag, false, false);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图表为空");
        }

        // 先修改图表状态为“执行中”，等执行结束后修改为“已完成”，保存执行结果；执行失败后，状态修改为“失败”，记录任务失败信息
        Chart updateChart = new Chart();
        updateChart.setId(chart.getId());
        updateChart.setStatus("running");
        boolean b = chartService.updateById(updateChart);
        if(!b){
            channel.basicNack(deliveryTag, false, false);
            handleChartUpdateError(chart.getId(), "图表状态更改失败");
            return;
        }

        // 调用 AIManager 服务
        String aiResult = aiManager.doChat(buildUserInput(chart));
        // 对得到的数据进行拆分
        String[] splits = aiResult.split("【【【【【");
        if(splits.length < 3){
            channel.basicNack(deliveryTag, false, false);
            handleChartUpdateError(chart.getId(), "AI生成错误");
            return;
        }
        // 将生成的 图表代码 和 文字结论 分开进行返回
        String genChart = splits[1].trim();
        String genResult = splits[2].trim();

        Chart updateChartResult = new Chart();
        updateChartResult.setId(chart.getId());
        updateChartResult.setGenChart(genChart);
        updateChartResult.setGenResult(genResult);
        updateChartResult.setStatus("succeed");
        boolean updateResult = chartService.updateById(updateChartResult);
        if(!updateResult){
            channel.basicNack(deliveryTag, false, false);
            handleChartUpdateError(chart.getId(), "更新图表成功状态失败");
            return;
        }

        // 手动消息确认
        channel.basicAck(deliveryTag, false);  // 一次确认一条消息
    }

    // 在此处构建用户的输入信息
    private String buildUserInput(Chart chart){
        String goal = chart.getGoal();
        String chartType = chart.getChartType();
        String result = chart.getChartData();

        // 处理用户输入
        StringBuilder userInput = new StringBuilder();
        userInput.append("分析目标: ").append("\n");

        // 确定生成的表格类型
        String userGoal = goal;
        if(StringUtils.isNotBlank(chartType)){
            userGoal += ", 请使用" + chartType;
        }
        userInput.append(userGoal).append("\n");
        userInput.append("我的数据：").append(result).append("\n");
        return userInput.toString();
    }

    private void handleChartUpdateError(long chartId, String execMessage){
        Chart updateChartResult = new Chart();
        updateChartResult.setId(chartId);
        updateChartResult.setStatus("failed");
        updateChartResult.setExecMessage("execMessage");
        boolean updateResult = chartService.updateById(updateChartResult);
        if(!updateResult){
            log.error("更新图表失败" + chartId + "," + execMessage);
        }
    }
}
