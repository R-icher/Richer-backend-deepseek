package com.yupi.springbootinit.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.exception.ThrowUtils;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.service.ChartService;
import com.yupi.springbootinit.mapper.ChartMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 动态建表+查询
 */
@Service
public class ChartServiceImpl extends ServiceImpl<ChartMapper, Chart>
    implements ChartService{

    @Resource
    private ChartMapper chartMapper;

    @Override
    public void saveCVSData(String cvsData, Long chartId) {
        // 将表格数据的第一行提取出来
        String[] columnHeaders = cvsData.split("\n")[0].split(",");
        StringBuilder sqlColumns = new StringBuilder();
        for (String header : columnHeaders) {
            // 非空校验
            ThrowUtils.throwIf(StringUtils.isAnyBlank(header), ErrorCode.PARAMS_ERROR);
            sqlColumns.append("`").append(header).append("` varchar(50) NOT NULL").append(", ");
        }

        /**
         * CREATE TABLE charts_3 (
         *   `日期` varchar(50) NOT NULL,
         *   `用户数` varchar(50) NOT NULL
         * )
         */
        String sql = String.format(
                "CREATE TABLE charts_%d (%s)",
                chartId, sqlColumns.substring(0, sqlColumns.length() - 2)
        );

        // 拼接 Insert 语句
        String[] rows = cvsData.split("\n");  // 读取接下来表格下的每一行数据

        /**
         * INSERT INTO charts_3 VALUES
         *   ('1号','10'),
         *   ('2号','20'),
         *   ('3号','30')
         */
        StringBuilder insertSql = new StringBuilder()
                .append("INSERT INTO charts_").append(chartId).append(" VALUES ");
        for(int i = 1; i < rows.length; i++){
            String[] cells = rows[i].split(",");
            insertSql.append("(").append("`").append(cells[0]).append("`,`").append(cells[1]).append("`").append(")");
            if(i < rows.length - 1){
                insertSql.append(", ");
            }
        }

        // 将新建的动态表插入数据库
        try {
            chartMapper.createTable(sql);
            chartMapper.insertValue(insertSql.toString());
        } catch (Exception e) {
            log.error("插入数据报错 " + e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR);
        }
    }

    /**
     * 查询保存到数据库之中的 cvs 数据
     * @param chartId
     * @return
     */
    @Override
    public List<Map<String, Object>> queryChartData(Long chartId) {
        return chartMapper.queryChartData(chartId);
    }
}




