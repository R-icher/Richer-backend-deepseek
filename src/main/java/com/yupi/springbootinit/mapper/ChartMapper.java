package com.yupi.springbootinit.mapper;

import com.yupi.springbootinit.model.entity.Chart;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;
import java.util.Map;

/**
 * 动态建表
 */
public interface ChartMapper extends BaseMapper<Chart> {
    /**
     * 动态的创建数据库
     * @param creatTableSQL
     */
    void createTable(final String creatTableSQL);

    /**
     * 向动态创建的数据库之中插入数据
     *
     * @param insertCVSData
     * @return
     */
    void insertValue(final String insertCVSData);

    /**
     * 查询保存数据表的信息
     *
     * @param tableName
     * @return
     */
    List<Map<String, Object>> queryChartData(final Long tableName);
}




