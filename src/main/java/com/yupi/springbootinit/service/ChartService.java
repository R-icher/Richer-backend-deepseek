package com.yupi.springbootinit.service;

import com.yupi.springbootinit.model.entity.Chart;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Map;


/**
 * 动态创建数据
 */
public interface ChartService extends IService<Chart> {

    void saveCVSData(String cvsData, Long chartId);

    List<Map<String, Object>> queryChartData(Long chartId);
}
