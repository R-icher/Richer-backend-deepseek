数据可视化系统（BI）





# 需求分析：

1. 智能分析：用户输入目标和原始数据，可以自动生成图表和分析结论
2. 图标管理的CRUD
3. 图标生成异步化（消息队列）【由于AI生成图表不是瞬时的，遇到多用户同时访问时，会影响并发量】
4. 对接AI（AIGC）





# 架构图

<img src="https://pic.code-nav.cn/course_picture/1601072287388278786/RLiG4sUAstE8dm9h.webp" alt="img" style="zoom:50%;" />





# 技术栈

1. Spring Boot (万用 Java 后端项目模板，快速搭建基础框架，避免重复写代码)
2. MySQL数据库
3. MyBatis Plus数据访问框架
4. 消息队列(RabbitMQ)
5. AI 能力(Open AI接口开发 / 星球提供现成的 AI 接口)
6. Excel 的上传和数据的解析(Easy Excel)
7. Swagger + Knife4j 项目接口文档
8. Hutool 工具库





# 库表设计

用户表，用户生成的图标信息的图表表（可视化图表，AI生成的数据结论），





# 反爬虫设计

```Java
        long current = chartQueryRequest.getCurrent();  // 请求的页码
        long size = chartQueryRequest.getPageSize();  // 请求的每页条数
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
```

**爬虫无法一次拉取百万级或几十万级数据**：每次调用 API，最多只能拿到 20 条记录

结合页码 `current`，爬虫要把整个数据集扫一遍，就必须发起很多次请求，每次抓 20 条，大大增加被监控、被封 IP 的风险





# 智能分析业务流程

用户输入：

​	分析目标

​	上传原始数据（excel）

​	更精细化的控制图表：比如图表类型，图表名称等信息

后端校验：

1. ​	校验用户的输入是否合法
2. ​	成本控制（AI开放平台是需要收费的，需要校验用户输入问题的次数统计，鉴权等）
3. ​	把处理后的数据输入给 AI 模型（调用 AI）
4. ​	把 AI 生成的图标信息和结论文本在前端进行展示





# 原始数据压缩

AI 接口普遍都有输入字数限制，尽可能压缩数据，能够允许多传一些数据

如何向 AI 题词（prompt）



## 用CSV对excel文件数据进行提取和压缩

1，使用 `easyExcel` 工具进行对 excel 数据的读取

2，根据 excel  表格的格式，可以分成读取表头数据和表中数据，通过 `LinkedHashMap` 来确保性能，从上往下依次读取

```java
LinkedHashMap<Integer, String> headerMap = (LinkedHashMap<Integer, String>) list.get(0);
```

3，依次读取表中数据

```Java
// 读取数据
        for(int i = 1; i < list.size(); i++){
            LinkedHashMap<Integer, String> dataMap = (LinkedHashMap<Integer, String>) list.get(i);
            List<String> dataList = dataMap.values().stream().filter(ObjectUtils::isNotEmpty).toList();
            stringBuilder.append(StringUtils.join(dataList, ",")).append("\n");
        }
```





# 为了确保Ai分辨问题

为了防止ai 无法分辨**压缩后的数据**和用户输入的**需求的话语**，需要开发人员做出区分：（将数据和需求分开）

```Java
        // 处理用户输入
        StringBuilder userInput = new StringBuilder();
        userInput.append("分析目标: ").append(goal).append("\n");

        // 压缩后的数据
        String result = ExcelUtils.excelToCSV(multipartFile);
        userInput.append("我的数据：").append(result).append("\n");
```





# （面试）为AI定义预设，设置其角色（预设prompt）

为了让ai的性能更高，需要提前设置 AI 的角色，让他处理更精细一方面的问题。

最简单的系统预设：你是一个数据分析师，接下来我会给你我的分析目标和原始数据，请告诉我分析结论。



1. 为了让 ai 能够在前端能够展示出 `ECharts` 类型的图表代码，需要的预设需要更加精确， prompt设置需要带上 {} ，让ai能够更敏感得识别出来：

```
你是一个数据分析师和前端开发专家，接下来我会按照固定格式给你提供内容：
分析需求：
{数据分析得需求或者目标}
原始数据：
{csv格式的原始数据，用 , 作为分隔符}
请根据以上内容，帮我生成数据分析结论和可视化图表代码
```



2. 控制输出格式，便于 AI 返回的内容能够更加方便地为我们所用

```
请根据以上内容，帮我按照指定格式生成内容（此外不要输出任何多余的开头，结尾等内容）
【【【【【    // 注意这里是代码分割的意思，被这个符号包起来的是生成的前端代码
{前端 Echarts V5 的 option 配置对象js代码，合理的将数据可视化，不要生成注释}     // 在这里需要制定生成前端生成的图表格式，指定类库
【【【【【
{图表的描述，以及明确的数据分析结论，越详细越好}
```





# 调用 AI 大模型的方法：

首先我们需要指定 AI 的 API-URL：

```java
private final static String API_URL = "https://api.siliconflow.cn/v1/chat/completions";
```



将  SDK 的密钥放入到 `application.yml` 配置文件中：

```yml
siliconflow:
  apiKey: sk-ihswimxybbexaokyzbhmpliaddcktjrsblbgamoxbjqmhqkg
```

然后在 `AIManager` 类中注入这个参数：

```java
    @Value("${siliconflow.apiKey}")
    private String apiKey;
```





然后我们需要制定我们用什么 AI 模型：

```Java
JSONObject requestBody = new JSONObject();
requestBody.set("model", "deepseek-ai/DeepSeek-V3");
```



将用户的分析需求和预设的 prompt 结合起来：

```Java
        JSONArray messages = new JSONArray();


        String systemPrompt = "\"你是一个数据分析师和前端开发专家，接下来我会按照以下固定格式给你提供内容：\\n\" +\n" +
                "                \"分析需求：\\n\" +\n" +
                "                \"{数据分析的需求或者目标}\\n\" +\n" +
                "                \"原始数据：\\n\" +\n" +
                "                \"{csv格式的原始数据，用,作为分隔符}\\n\" +\n" +
                "                \"请根据这两部分内容，按照以下指定格式生成内容（此外不要输出任何多余的开头、结尾、注释）\\n\" +\n" +
                "                \"【【【【【\\n\" +\n" +
                "                \"{前端 Echarts V5 的 option 配置对象的json代码，合理地将数据进行可视化，不要生成任何多余的内容，比如注释}\\n\" +\n" +
                "                \"【【【【【\\n\" +\n" +
                "                \"{明确的数据分析结论、越详细越好，不要生成多余的注释}\"";


        messages.add(new JSONObject().set("role", "system").set("content", systemPrompt));
		// message 是用户输入的向AI提问的需求
        messages.add(new JSONObject().set("role", "user").set("content", message));
        requestBody.set("messages",messages);
```



将数据封装好发送给 AI，并接收 AI 响应回来的数据，发送给客户端：

***注意 choice 字段，AI 返回的数据就是封装在 choice 字段内的，因此我们需要去获取 choice 字段内的内容***

```java
    // 将内容发送给 AI，需要指定 apiKey 密钥，以及发送的请求格式是 json
    // 然后在请求体内设置要发送给 AI 的数据，就是刚才封装好的 requestBody
    HttpResponse response = HttpRequest.post(API_URL)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .body(requestBody.toString())
            .execute();
    // 检查数据返回是否成功
    if (response.getStatus() != 200){
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, response.getStatus()+ " AI 响应错误,"+response.body());
    }

    // 将回信的文本内容转换成程序能理解的JSON对象
    JSONObject responseJson = JSONUtil.parseObj(response.body());
    // 提取 AI 返回的 choice 字段，因为 choice 字段就是 AI 生成返回的结果
    JSONArray choices = responseJson.getJSONArray("choices");
    if (choices.isEmpty()) {
        throw new BusinessException(ErrorCode.OPERATION_ERROR,"API返回结果为空");
    }
    // 提取生成的文本
    return choices.getJSONObject(0)
            .getJSONObject("message")
            .getStr("content");
}
```

以下是 AI 返回的数据格式：**（我们要从中获取message字段下的content字段）**

```json
{
  "id": "chatcmpl-xxxxxxxx",
  "object": "chat.completion",
  "created": 1721803260,
  "model": "deepseek-ai/DeepSeek-V3",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "【【【【【\n{ \"title\": {...}, \"series\": [...] }\n【【【【【\n数据分析结论：...\n"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": { ... }
}
```





# Controller层去调用AI模型类

主要是对数据做一些拼接功能，我们之前在 prompt 中定义了 `【【【【【` 作为前端代码和结论之间的分隔符：

```Java
        // 调用 AIManager 服务
        String aiResult = aiManager.doChat(userInput.toString());
        // 对得到的数据进行拆分
        String[] splits = aiResult.split("【【【【【");
        if(splits.length < 3){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI生成错误");
        }
        // 将生成的 图表代码 和 文字结论 分开进行返回
        String genChart = splits[1].trim();
        String genResult = splits[2].trim();
```

我们可以将这两段内容封装到一个类中再返回给前端：

```Java
// 保存到数据库
        boolean save = chartService.save(chart);
        ThrowUtils.throwIf(!save, ErrorCode.SYSTEM_ERROR, "图表保存失败");
```









# （面试）安全性问题

1，用户如果上传了一个超大的文件怎么办（需要校验文件）？

2，如果用户用科技疯狂点击提交怎么办（要考虑限流）

3，数据存储：现在每个图表的原始数据都放在同一个 chart 表中，后期数据量大的情况下，会导致查询图表或查询 chart 等操作变得缓慢





1，简单实现：限制上传文件的大小+校验文件后缀名是否合法（自定义文件后缀名白名单）

```Java
/**
         * 校验用户上传的文件，限制上传文件的大小
         */
        Long size = multipartFile.getSize();  // 获取到文件的大小
        String originalFilename = multipartFile.getOriginalFilename();  // 拿到原始文件名
        final Long ONE_MB = 1024 * 1024L;
        ThrowUtils.throwIf(size > ONE_MB, ErrorCode.PARAMS_ERROR, "文件大小超过 1M");

        // 校验文件后缀名是否合法
        String suffix = FileUtil.getSuffix(originalFilename);
        // 自定义文件白名单，这些文件后缀名是合法的
        final List<String> validFileSuffixList = Arrays.asList("png", "jpg", "svg", "webp", "jpeg");
        ThrowUtils.throwIf(!validFileSuffixList.contains(suffix), ErrorCode.PARAMS_ERROR, "文件后缀非法");
```







2，对于 BI 平台，用户是有查看原始数据，对数据进行简单查询的需求的：例如在原始数据中有【日期】【用户数】两列，现在用户想要查询日期中的3号。那么此时后端就要把所有的两列先都查出来，然后再根据【日期】列进行过滤。

***（最好不要把原始数据放在数据库的一个小格子中）***

***解决方案是把用户上传的原始数据单独存放在一个表中（分库分表）***

------

存储图表信息时，不把数据存储为字段，而是新建一个 `chart_{图表id}` 的数据表，

通过图表 id，数据列名，数据类型等字段，生成以下 `sql` 语句，并且执行即可：

```sql
create table chart_12229827363800
(
	日期 int null,
	用户数 int null
);
```



- ***动态sql实现***

`ChartMapper` 定义动态 SQL 方法：

```java
public interface ChartMapper extends BaseMapper<Chart> {
  void createTable(@Param("ddl") String ddl);
  void insertValue(@Param("dml") String dml);
  List<Map<String,Object>> queryChartData(@Param("suffix") String suffix,
                                          @Param("date")   String date);
}
```

对应的 XML 片段：

```java
<update id="createTable">
  ${ddl}
</update>

<insert id="insertValue">
  ${dml}
</insert>

<select id="queryChartData" resultType="java.util.Map">
  SELECT *
    FROM charts_${suffix}
   WHERE 日期 = #{date}
</select>

```



然后需要在`ServiceImpl`层把对应的 `sql` 语句拼接出来，（`buildCreateTableSql`，`buildInsertSql` 需要自己实现）

```Java
    // 3. 拼 DDL
    String createSql = buildCreateTableSql(tableName, List.of("日期","用户数"));
    baseMapper.createTable(createSql);

    // 4. 拼 DML
    String insertSql = buildInsertSql(tableName, List.of("日期","用户数"), records);
    baseMapper.insertValue(insertSql);
```

**例如：**

```Java
public String buildCreateTableSql(String tableName, List<String> columns) {
    StringBuilder sb = new StringBuilder();
    sb.append("CREATE TABLE IF NOT EXISTS ")
      .append(tableName)
      .append(" (");
    for (int i = 0; i < columns.size(); i++) {
        String col = columns.get(i);
        // 简单示例：全部当 VARCHAR(255)
        sb.append("`").append(col).append("` VARCHAR(255)");
        if (i < columns.size() - 1) sb.append(", ");
    }
    sb.append(");");
    return sb.toString();
}
```







3，限流

思考限流阈值多大合适，参考正常用户的使用，比如限制单个用户每秒只能使用一次

------

- ***限流实现：***

1. 本地限流（单机限流）

每个服务器单独限流，一般适用于单体项目，就是你的项目只有一个服务器

`Guava Rate﻿Limiter`：这是谷歌 `Gu⁢ava` 库提供的限流工具，可以‍对单位时间内的请求数量进行限制。

```java
import com.google.common.util.concurrent.RateLimiter;

public static void main(String[] args) {
    // 每秒限流5个请求
    RateLimiter limiter = RateLimiter.create(5.0);
    while (true) {
        if (limiter.tryAcquire()) {
            // 处理请求
        } else {
            // 超过流量限制，需要做何处理
        }
    }
}

```



2. 分布式限流（多机限流）

如果项目有多个服务器，比如微服务，那么建议使用分布式限流

把用户的使用频率等数据放到一个集中的存储进行统计，比如Redis，这样无论用户的请求落到了哪台服务器，都以集中的数据存储内的数据为准。（`Redisson`）



## （面试）限流算法实现

1. 固定窗口限流

单位时间内允许部分操作：1小时只允许10个用户操作。

可能会导致流量突刺：前59分钟一个操作，第59分钟10个操作，第一小时01分又有10个操作，相当于2分钟内执行了20次操作。

------

2. 滑动窗口限流

单位时间是滑动的，需要指定一个滑动单位。

第59分钟时，限流窗口是 59分 - 1小时59分，这个时间段内只能接受10次请求，只要还在这个窗口内，更多的操作就会被拒绝。

限流效果和滑动单位有关，往往很难选取到一个特别合适的滑动单位

------

3. 漏桶限流

以固定的速率处理请求，当请求满了后，拒绝请求。（类似一个每秒都在往下漏一滴水的桶）

每秒处理10个请求，桶容量是10，每0.1秒固定处理一次请求，如果一秒内来了11个请求，最后那个请求就会溢出被拒绝

***缺点：无法迅速处理一批请求，只能一个个按顺序来。***

<img src="./智能BI.assets/0d05876c844642c3878aaa829549bf63tplv-k3u1fbpfcp-zoom-in-crop-mark1512000.webp" alt="img" style="zoom:50%;" />

------

4. 令牌桶算法

桶里装的都是令牌（令牌是以固定速率生成的），用户能够并发的去获取这些令牌，然后进行接下来的操作：

<img src="./智能BI.assets/9f8efde12a1a4fb8b902d910a90f104btplv-k3u1fbpfcp-zoom-in-crop-mark1512000.webp" alt="img" style="zoom:50%;" />

------





# （面试）Redisson实现限流

`Redisson` 是一个⁠开源的 Java Redis 客户端，也是一个分布式数据网格，提供了基于 Redis 的各种分布﻿式数据结构和便捷的操作接口

对于 `Redisson` 的配置：（类似Redis）

```java
@Configuration
@ConfigurationProperties(prefix = "spring.redis")
@Data
public class RedissonConfig {

    private Integer dataBase;
    private String host;
    private Integer port;

    public RedissonClient getRedissonClient(){
        Config config = new Config();
        config.useSingleServer()
                .setDatabase(dataBase)
                .setAddress("redis://" + host + ":" + port);
        RedissonClient redisson = Redisson.create(config);
        return redisson;
    }
}
```





- ***`Redisson` 的 `RateLimiter` 如何使用***

具体的实现方式如下：

1）集中管理⁠限流器：创建一个 `RedisLimiterMan﻿ager` 类，集中管理整⁢个项目中所有的限流器，并‍提供创建限流器的接口。

2）创建限流器：⁠通过向 `redissonClient` 传入指定的 key 来﻿创建限流器，每个用户对应的 k⁢ey 不同，对应的限流器也不同‍，从而实现不同用户的独立限流

3）设置限⁠流规则：通过 `rateLimiter` 的﻿ `trySetRat⁢e` 方法制定限流规则‍，每 1 秒内最多获取 2 个令牌

4）请求令牌：当用户要执行操作时，会执行对应 `rateLimiter` 的 `tryAcquire` 方法尝试获取令牌，如果能够获取到，可以执行后续操作，否则抛出 TOO_MANY_REQUEST 异常。

------

```java
    @Resource
    private RedissonClient redissonClient;

    /**
     * 限流操作
     */
    public void doPateLimit(String key){
        // 创建一个名称为user_limiter的限流器，每秒最多访问 2 次
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
        // 限流器的统计规则(每秒2个令牌;连续的请求,最多只能有1个请求被允许通过)
        // RateType.OVERALL表示速率限制作用于整个令牌桶,即限制所有请求的速率
        rateLimiter.trySetRate(RateType.OVERALL, 2, Duration.ofSeconds(1));
        // 每当一个操作来了后，请求一个令牌
        boolean canOp = rateLimiter.tryAcquire(1);
        // 如果没有令牌,还想执行操作,就抛出异常
        if (!canOp) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUEST);
        }
    }
```



***一些细节：***（这里设置一次性拿多少个令牌，可以设置 `vip` 用户和普通用户，根据桶内令牌的数量，用户去拿取令牌，`vip`用户可以一次性请求1个令牌【这样流量就会更多，可以申请更多次key】。普通用户一次性请求5个令牌【这样从桶中获取令牌的时候就只能拿几次就被限流了】

```Java
        // 每当一个操作来了后，请求一个令牌
        boolean canOp = rateLimiter.tryAcquire(1);
```



调用的时候：针对某个用户 x 方法限流，比如单个用户单位时间内最多执行 xx 次这个方法：（Controller层）

***在这里结合上面的代码就是指每个用户每秒最多只能发送两次请求***

```Java
        // 限流判断
        redisLimiterManager.doPateLimit("genChartByAi_" + String.valueOf(loginUser.getId()));
```









# （面试）同步和异步

1. 业务服务器可能会有很多请求在处理，导致系统资源紧张，严重时导致服务器宕机或者无法处理新的请求

2. 调用第三方AI服务的能力是有限的，比如每 3 秒只能处理 1 个请求，会导致 AI 处理不过来，严重时 AI 可能会对我们的后台系统拒绝服务。



- ***业务分析流程***

1. 当用户要进行耗时很长的操作时，点击提交后，不要在界面傻等，而是应该把这个任务保存到数据库中记录下来
2. 把用户执行的操作（任务）放到一个**任务队列（不是直接拒绝需求而是先让它等待）**中：

​		a. 任务队列没有满，如果我们的程序还有多余的空闲线程，可以立刻去做这个任务

​		    如果程序的线程都在繁忙，无法继续处理，就放到等待队列里。

​		b. 程序都在忙，任务队列满了：

​			(1) 拒绝掉这个任务，再也不去执行

​			(2) 通过保存到数据库中的记录来看到提交失败的任务，并且在程序闲的时候，可以把任务从数据库中捞到程序里再去执行

------

3. 我们的线程从任务队列中取出任务依次执行，没完成一件事要修改一下任务的状态。

4. 用户可以查询任务的执行状态，或者在任务执行成功或失败的时候能得到通知（发邮件，短信，系统消息提示等）

   ------

5. 如果要执行的任务非常复杂，包含很多环节，在每一个小任务完成时，要程序（数据库中）记录下任务的执行状态（进度）





- ***标准异步化业务流程***

1，用户点击智能分析页的提交按钮时，先把图表立刻保存到数据库中（作为一个任务）

2，用户可以在图标管理页面查看所有图表（已生成的，生成中的，未生成的）





# （面试）线程池

- 在 Java 中，可以使用JUC并发编程包中的 `ThreadPoolExecutor`，来实现非常灵活地自定义线程池。



一个饭店的故事：一个服务员对应服务一个客户，来一个客户就雇一个服务员，饭店中有3个核心员工和三个临时员工，临时员工服务完顾客之后没有顾客来了就会被辞退。如果饭店人数多了，顾客就会排长队，为了避免顾客人数过多，当排队人数过多，超出上限人数的顾客就下次再来。

几个关键点和自定义线程池之间的关系转换：

<img src="./智能BI.assets/屏幕截图 2025-04-22 220532.png" style="zoom: 33%;" />

<img src="./智能BI.assets/屏幕截图 2025-04-22 220758.png" style="zoom:50%;" />

注意上图：当有8个任务的时候，线程池会**先调用三个核心线程**执行三个任务，然后**将剩余的三个任务放入到队列**中进行排队。如果这个时候还剩余任务，就如上这个例子中还剩余任务7和任务8，线程池才会调用临时线程去执行任务7和8。**（临时线程优先级最弱）**

**注意临时线程的登场时间就是核心线程都在忙，队伍都占满了的情况下才会调用临时线程。**



- ***任务拒绝策略：***

如果任务数量已经超过了  核心线程+临时线程+队伍长度，就会把多出来的任务拒绝。

具体代码实现：<img src="./智能BI.assets/屏幕截图 2025-04-22 222758.png" style="zoom: 50%;" />

自定义线程池结束之后就直接submit提交即可。

------

***注意：线程工厂和拒绝策略是两个接口，有时是需要自己书写其实现类的：（比如工厂的实现类）***

```Java
    /**
     * 自定义线程工厂 ，这里就是为每个线程命名
     */
    ThreadFactory threadFactory = new ThreadFactory() {
        private int count = 1;
        @Override
        public Thread newThread(@NotNull Runnable r) {
            Thread thread = new Thread();
            thread.setName("线程：" + count);
            return thread;
        }
    };
```



在 `Controller` 层做测试的时候：`CompletableFuture.runAsync()` 用来**执行一个异步任务**，两个参数**一个是指定线程做什么**，**一个是指定用哪个线程池来执行这个任务**

```java
    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    @GetMapping("/add")
    public void add(String name){
        CompletableFuture.runAsync(() -> {
            System.out.println("任务执行中" + name + "，执行人：" + Thread.currentThread().getName());
            try {
                Thread.sleep(600000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }, threadPoolExecutor);
    }
```

------

若要获取线程池的各种信息，***可以直接调用 `ThreadPoolExecutor` 内嵌的各种 get 方法：***

```java
    // 返回该线程池的状态信息
    @GetMapping("/get")
    public String get(){
        Map<String, Object> map = new HashMap<>();
        int size = threadPoolExecutor.getQueue().size();
        map.put("队列长度", size);
        long taskCount = threadPoolExecutor.getTaskCount();
        map.put("任务总数", taskCount);
        long completedTaskCount = threadPoolExecutor.getCompletedTaskCount();
        map.put("已完成的任务数", completedTaskCount);
        int activeCount = threadPoolExecutor.getActiveCount();
        map.put("正在工作的线程数", activeCount);
        return JSONUtil.toJsonStr(map);
    }
```







# 项目中的异步化线程池操作

在项目中我把调用 AI 的这部分代码放到了 `CompletableFuture.runAsync()` 内，指定开启了异步操作：

1. 注意在执行异步化操作之前需要先**将AI还未生成的图表信息全部保存到数据库内**，状态设置为执行中
2. AI 将对应数据生成之后，再将 AI 生成的图表信息保存到数据库中，状态更新为 succeed
3. 注意图表更新失败的情况，需要把状态设置为 failed

```Java
        // 将生成的表格插入到数据库当中
        Chart chart = new Chart();
        chart.setName(name);
        chart.setGoal(goal);
        chart.setChartData(result);
        chart.setChartType(chartType);
        chart.setUserId(loginUser.getId());
        chart.setStatus("wait");
        // 保存到数据库
        boolean save = chartService.save(chart);
        ThrowUtils.throwIf(!save, ErrorCode.SYSTEM_ERROR, "图表保存失败");

        // 让调用 AI 的步骤实现异步化
        CompletableFuture.runAsync(() -> {
            // 先修改图表状态为“执行中”，等执行结束后修改为“已完成”，保存执行结果；执行失败后，状态修改为“失败”，记录任务失败信息
            Chart updateChart = new Chart();
            updateChart.setId(chart.getId());
            updateChart.setStatus("running");
            boolean b = chartService.updateById(updateChart);
            if(!b){
                handleChartUpdateError(chart.getId(), "图表状态更改失败");
                return;
            }

            // 调用 AIManager 服务
            String aiResult = aiManager.doChat(userInput.toString());
            // 对得到的数据进行拆分
            String[] splits = aiResult.split("【【【【【");
            if(splits.length < 3){
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
                handleChartUpdateError(chart.getId(), "更新图表成功状态失败");
                return;
            }
        }, threadPoolExecutor);

        // 定义一个返回对象把这两部分封装进入返回对象
        BIResponse biResponse = new BIResponse();
        biResponse.setChartId(chart.getId());

        return ResultUtils.success(biResponse);
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
```





# （简历）使用反向压力优化线程池

在之前的写法中，核心线程数是写死的，导致性能永远是固定的，压力大的时候性能不够用，压力小的时候浪费资源。

因此可以通过创建一个监听类，来定时查看阻塞队列中的任务数量（例如5秒查看一次），如果发现阻塞队列中的任务数量过多，就增加核心线程数；反之就减少核心线程数（最大化利用资源）

改造后的代码：

------

- ***（线程池配置类）***

```Java
@Configuration
public class ThreadPoolExecutorConfig {

    /**
     * 自定义线程工厂 ，这里就是为每个线程命名
     */
    ThreadFactory threadFactory = new ThreadFactory() {
        private int count = 1;
        @Override
        public Thread newThread(@NotNull Runnable r) {
            Thread thread = new Thread(r);
            thread.setName("线程：" + count);
            count++;
            return thread;
        }
    };

    @Bean
    public ThreadPoolExecutor threadPoolExecutor(){
        ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(2, 10,
                100, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10), threadFactory);
        return threadPoolExecutor;
    }

    // 设置一个监听器类，用来监视阻塞队列中有多少个正在等待的任务
    @Bean
    public AiQueueMonitor aiQueueMonitor(ThreadPoolExecutor executor) {
        return new AiQueueMonitor(executor);
    }
}
```

- ***（`AiQueueMonitor`监听类）【只需要实现计算阻塞队列中的任务数量】***

```Java
@Component
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
```

- ***（核心的功能：对核心线程数量进行扩容和缩小的逻辑）***

```Java
@Component
@Slf4j
public class DynamicThreadPoolScaler {
    // 注入线程池
    private final ThreadPoolExecutor threadPoolExecutor;

    // 注入任务控制器，监视任务队列的长度
    private final AiQueueMonitor aiQueueMonitor;

    // 配置阈值:当队列长度超过 highThreshold 时，扩容；低于 lowThreshold 时，缩容
    private final int minCore = 2;
    private final int maxCore = 10;
    private final int highThreshold = 8;
    private final int lowThreshold = 5;

    public DynamicThreadPoolScaler(ThreadPoolExecutor threadPoolExecutor, AiQueueMonitor aiQueueMonitor) {
        this.threadPoolExecutor = threadPoolExecutor;
        this.aiQueueMonitor = aiQueueMonitor;
    }

    // 每五秒扫描一次队列
    @Scheduled(fixedDelay = 5_000)
    public void scalePool(){
        int queueSize = aiQueueMonitor.getCurrentQueueSize();
        int currentCore = threadPoolExecutor.getCorePoolSize();
        int newCore = currentCore;

        if(queueSize > highThreshold && currentCore < maxCore){
            // 压力变大，将核心线程数扩大
            newCore = Math.min(maxCore, currentCore + 1);
        }else if(queueSize < lowThreshold && currentCore > minCore){
            // 压力变小，减少核心线程数
            newCore = Math.max(minCore, currentCore - 1);
        }

        if(newCore != currentCore){
            threadPoolExecutor.setCorePoolSize(newCore);  // 更新核心线程数
            log.info("调整线程池核心线程数：{} → {}（队列长度={}）",
                    currentCore, newCore, queueSize);
        }
    }
}
```









# 系统不足：

- **无法集中限制，仅能单机限制：**如果AI服务的使用限制为同时只能有两个用户，我们可以通过限制单个线程池的最大核心线程数为 2 来实现这个限制。但是，当系统需要扩展到分布式，即多台服务器时，每台服务器都需要有 2 个线程，这样就可能会产生 2N 个线程，超过 AI 服务的限制。因此，我们需要一个集中的管理方式来分发任务，例如统一存储当前正在执行的任务数。



- **由于任务存储在内存中执行，可能会丢失：**我们可以通过从数据库中人工提取并重试，但这需要额外的开发(如定时任务)。然而，重试的场景是非常典型的，我们不需要过于关注或自行实现。一个可能的解决方案是将任务存储在可以持久化的硬盘中。



- **优化：**随着系统功能的增加，长耗时任务也越来越多，系统会变得越来越复杂（比如需要开启多个线程池，可能出现资源抢占的问题）。一种可能的解决方案是服务拆分，即应用解耦。我们可以将长耗时、资源消耗大的任务单独抽成一个程序，以避免影响主业务。此外，我们也可以**引入一个"中间人"，让它帮助我们连接两个系统，比如核心系统和智能生成业务**





# 中间件

连接多个系统，帮助多个系统紧密协作的技术（或者组件）

例如：Redis，消息队列，分布式存储 `Etcd`





# 消息队列

模型：生产者(Producer)，消费者(Consumer)，消息(Message)，消息队列(Queue)

以快递柜的模型入手：

小王是一个快递⁠员（消息生产者），他在早上八点拿到了一个包裹（消息）。然而，收件人小受（消息消费者）说早上八点他有事，无法收快递，让小王晚上﻿八点再来送。此时，如果有一个快递柜（消息队列），小王就可以将包⁢裹存放在快递柜里，然后告诉小受密码，小受什么时候有时间就可以‍去取包裹。在这个过程中，小王并不需要等小受在家

<img src="./智能BI.assets/屏幕截图 2025-07-26 153840.png" style="zoom:50%;" />

- ***使得生产者和消费者实现了解耦，互不影响。***

------



使用场景：在某时间点来了10万个请求，把这10万个请求放入到消息队列中，处理系统以自己的恒定速率（比如每秒一个）慢慢执行，从而保护系统，稳定处理。







# （面试）分布式消息队列

优势：

1. 数据持久化：它可以把消息集中存储到硬盘里，服务器重启就不会丢失
2. 可扩展性：可以根据需求，随时增加（或减少）节点，继续保持稳定的服务
3. 应用解耦：可以连接各个不同语言，框架开发的系统，让这些系统能够灵活传输读取数据



<img src="./智能BI.assets/屏幕截图 2025-07-26 155612.png" style="zoom:50%;" />





## (面试)分布式消息队列应用场景

1. 应用解耦：解耦系统被不同的模块，使它们能够独立开发，部署和扩展
2. 异步处理：可以将耗时任务作为消息放入到消息队列当中，然后由单个或多个工作线程来处理，从而提高系统性能和响应速度
3. 流量削峰：当系统面临突发的高流量时，分布式消息队列可以通过请求排队的方式实现流量费平滑处理，防止过载
4. 分布式事务：有些分布式消息队列支持分布式事务，可以用于确保消息的可靠传递和处理





## 其他MQ技术

- 吞吐量：IO、并发
- 时效性：类似延迟，消息的发送、到达时间
- 可用性：系统可用的比率（比如 1 年 365 天宕机 1s，可用率大概 X 个 9）
- 可靠性：消息不丢失（比如不丢失订单）、功能正常完成

| **技术名称** | **吞吐量** | **时效性**     | **可用性** | **可靠性** | **优势**                                                 | **应用场景**                                                 |
| ------------ | ---------- | -------------- | ---------- | ---------- | -------------------------------------------------------- | ------------------------------------------------------------ |
| activemq     | 万级       | 高             | 高         | 高         | 简单易学                                                 | 中小型企业、项目                                             |
| rabbitmq     | 万级       | **极高(微秒)** | 高         | 高         | 生态好(基本什么语言都支持)、时效性高、易学               | 适合绝大多数分布式的应用，这也是先学他的原因                 |
| kafka        | **十万级** | 高(毫秒以内)   | **极高**   | **极高**   | 吞吐量大、可靠性、可用性，强大的数据流处理能力           | 适用于**大规模**处理数据的场景，比如构建日志收集系统、实时数据流传输、事件流收集传输 |
| rocketmq     | 十万级     | 高(ms)         | **极高**   | **极高**   | 吞吐量大、可靠性、可用性，可扩展性                       | 适用于**金融** 、电商等对可靠性要求较高的场景，适合**大规模**的消息处理。 |
| pulsar       | 十万级     | 高(ms)         | **极高**   | **极高**   | 可靠性、可用性很高，基于发布订阅模型，新兴(技术架构先进) | 适合大规模、高并发的分布式系统(云原生)。适合实时分析、事件流处理、IoT 数据处理等。 |







# RabbitMq的安装

1. `rabbitmq` 依赖 Erlang 的语言环境

2. 安装完 Erlang 之后，win+r 输入 `services.msc`（服务菜单），查看 `rabbitmq` 是否已启动

3. 安装 `RabbitMq` 监控面板，在 `rabbitmq` 安装目录的 `sbin` 中执行下述脚本

```bat
rabbitmq-plugins.bat enable rabbitmq_management
```

4. 若被拦截，可以自己创建管理员用户：[Authentication, Authorisation, Access Control | RabbitMQ](https://www.rabbitmq.com/docs/access-control)

<img src="./智能BI.assets/屏幕截图 2025-07-26 172059.png" style="zoom: 50%;" />





# RabbitMq整合Java

## HelloWorld demo

1. 引入消息队列到Java 客户端：

```xml
<!-- https://mvnrepository.com/artifact/com.rabbitmq/amqp-client -->
<dependency>
    <groupId>com.rabbitmq</groupId>
    <artifactId>amqp-client</artifactId>
    <version>5.25.0</version>
</dependency>
```

------

2. Hello World 的 demo 代码：**(接收端)**

```Java
public class Recv {

    private final static String QUEUE_NAME = "hello";

    public static void main(String[] argv) throws Exception {
        // 创建链接，指定连接的地址
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        Connection connection = factory.newConnection();
        
        // Channel频道：理解为操作消息队列的 client，提供了和消息队列 server 建立通信的传输方法(为了复用链接，提高传输效率)
        Channel channel = connection.createChannel();

        // 创建出一个消息队列，几个参数的含义：
        // queueName：消息队列名称（注意，同名称的消息队列，只能用同样的参数创建一次）
		// durabale：消息队列重启后，消息是否丢失
		// exclusive：是否只允许当前这个创建消息队列的连接操作消息队列
		// autoDelete：没有人用队列后，是否要删除队列
        channel.queueDeclare(QUEUE_NAME, false, false, false, null);
        System.out.println(" [*] Waiting for messages. To exit press CTRL+C");

        /**
        *delivery：代表 RabbitMQ 服务器推送过来的一条消息，包含：
	delivery.getBody() → byte[]，就是消息的原始内容（Producer 发过来的那段字节）。
	delivery.getEnvelope() → 可以拿到交换机（exchange）、路由键（routingKey）、DeliveryTag（用于手动 ACK）等元信息。
	delivery.getProperties() → 可以拿到消息的各种属性（timestamp、headers、自定义属性等）。
        */
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
            System.out.println(" [x] Received '" + message + "'");
        };
        
        // 每当有一条消息队列过来了，就会调用上面的 deliverCallback 方法
        channel.basicConsume(QUEUE_NAME, true, deliverCallback, consumerTag -> { });
    }
}
```

- ***发送端：***

```Java
public class Send {

    private final static String QUEUE_NAME = "hello";

    public static void main(String[] argv) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        try (Connection connection = factory.newConnection();
            Channel channel = connection.createChannel()) {
            channel.queueDeclare(QUEUE_NAME, false, false, false, null);
            String message = "Hello World!";
            channel.basicPublish("", QUEUE_NAME, null, message.getBytes(StandardCharsets.UTF_8));
            System.out.println(" [x] Sent '" + message + "'");
        }
    }
}
```





## 多消费者demo

一个生产者给一个队列发消息，多个消费者从这个队列取消息，1对多

- ***发送端的变化：***

```Java
        channel.basicPublish("", TASK_QUEUE_NAME,   // 依旧是队列名称
          MessageProperties.PERSISTENT_TEXT_PLAIN,  // 这里一行指开启了消息持久化，服务器重启之后，队列中的消息不会丢失
          message.getBytes("UTF-8"));
```

- ***接收端变化：***

为了提高并发能力，可以设置下面这条代码内的参数：

```java
// 每个消费者最多同时处理1个任务
channel.basicQos(1);
```



- ***自动确认消息：***

在代码的最后有如下语句，第二个参数的false 为 `autoack`，默认为false----消息确认机制，**这里相当于接收到消息，立刻就成功了**，建议设为false

```Java
 channel.basicConsume(TASK_QUEUE_NAME, false, deliverCallback, consumerTag -> {});
```



- ***手动确认消息方法：***

第二个参数⁠ 'multiple' 表示批量确认，也﻿就是说，是否需要一次⁢性确认所有的历史消息‍，直到当前这条消息为止。

第三个参数表示是否重新入队，可用于重试。

```java
channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false);
```





## (面试)交换机

一个生产者给多个队列发消息，一个生产者对多个队列

交换机的作用：类似路由器，提供转发功能。怎么把消息转发到不同的队列上

要解决的问题：怎么把消息转发到不同的队列上，好让消费者从不同的队列消费



- ***交换机类别（4种）***

1. **fanout：消息会被转发到所有绑定到该交换机的队列，适用于发布订阅场景。**



- ***绑定队列到某一个交换机：（将接收端创建出来的队列 `queueName` ，与交换机EXCHANGE_NAME进行绑定）***
- 除此之外还有第三个参数，`routingKey`(路由键)，控制消息要转发给哪个队列的，在fanout 类型中用不到

```Java
channel1.queueBind(queueName, EXCHANGE_NAME, "");
```



（发送端）

```Java
public class FanoutProducer {
  // 定义要使用的交换机名名称,这次的队列就改叫fanout-exchange
  private static final String EXCHANGE_NAME = "fanout-exchange";

  public static void main(String[] argv) throws Exception {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost("localhost");

    try (Connection connection = factory.newConnection();
         Channel channel = connection.createChannel()) {
        
    	// 声明fanout类型的交换机
        channel.exchangeDeclare(EXCHANGE_NAME, "fanout");
        
        // 创建一个Scanner对象来读取用户输入
        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNext()) {
            String message = scanner.nextLine();
            
            // 将消息发送到指定的交换机（fanout交换机），此时第一个参数就不能为空，要指定发送到的交换机9名
            channel.basicPublish(EXCHANGE_NAME, "", null, message.getBytes("UTF-8"));
            
            System.out.println(" [x] Sent '" + message + "'");
        }
    }
  }
}

```



（接收端）

```Java
public class FanoutConsumer {
  // 声明交换机名称为"fanout-exchange"
  private static final String EXCHANGE_NAME = "fanout-exchange";

  public static void main(String[] argv) throws Exception {
    // 创建连接工厂
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost("localhost");
    // 建立连接
    Connection connection = factory.newConnection();
    // 创建两个通道
    Channel channel1 = connection.createChannel();
    Channel channel2 = connection.createChannel();
      
    // 声明交换机
    channel1.exchangeDeclare(EXCHANGE_NAME, "fanout");
    
    // 创建队列1，随机分配一个队列名称
    String queueName = "xiaowang_queue";
    channel1.queueDeclare(queueName, true, false, false, null);
    channel1.queueBind(queueName, EXCHANGE_NAME, "");
	// 创建队列2
    String queueName2 = "xiaoli_queue";
    channel2.queueDeclare(queueName2, true, false, false, null);
    channel2.queueBind(queueName2, EXCHANGE_NAME, "");
    System.out.println(" [*] Waiting for messages. To exit press CTRL+C");
      
	// 创建交付回调函数1
    DeliverCallback deliverCallback1 = (consumerTag, delivery) -> {
      String message = new String(delivery.getBody(), "UTF-8");
      System.out.println(" [小王] Received '" + message + "'");
    };
	// 创建交付回调函数2
    DeliverCallback deliverCallback2 = (consumerTag, delivery) -> {
      String message = new String(delivery.getBody(), "UTF-8");
      System.out.println(" [小李] Received '" + message + "'");
    };
    
    // 开始消费消息队列1
    channel1.basicConsume(queueName, true, deliverCallback1, consumerTag -> { });
    // 开始消费消息队列2
    channel2.basicConsume(queueName2, true, deliverCallback2, consumerTag -> { });
  }
}
```





2. Direct 交换机

**消息会根据路由键转发到指定的队列，特定的消息只能交给特定的系统来处理**

（发送端）

```Java
public class DirectProducer {
  // 定义交换机名称为"direct-exchange"
  private static final String EXCHANGE_NAME = "direct-exchange";

  public static void main(String[] argv) throws Exception {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost("localhost");
    try (Connection connection = factory.newConnection();
        Channel channel = connection.createChannel()) {
        channel.exchangeDeclare(EXCHANGE_NAME, "direct");
        
    	// 创建一个Scanner对象用于读取用户输入
        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNext()) {
            // 读取用户输入的一行内容，并以空格分割
            // 用户输入内容和对应的路由键
            String userInput = scanner.nextLine();
            String[] strings = userInput.split(" ");
            
            // 如果输入内容不符合要求，继续读取下一行
            if (strings.length < 1) {
                continue;
            }
            
            // 获取消息内容和路由键
            String message = strings[0];
            String routingKey = strings[1];
            
        	// 这里需要指定路由键，以及绑定的交换机
            channel.basicPublish(EXCHANGE_NAME, routingKey, null, message.getBytes("UTF-8"));
            // 打印成功发送的消息信息，包括消息内容和路由键
            System.out.println(" [x] Sent '" + message + " with routing:" + routingKey + "'");
        }
    }
  }
  //..
}
```



（接收端）

```Java
package com.yupi.springbootinit.mq;

import com.rabbitmq.client.*;

public class DirectConsumer {
	// 定义我们正在监听的交换机名称"direct-exchange"
    private static final String EXCHANGE_NAME = "direct-exchange";

    public static void main(String[] argv) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();
        channel.exchangeDeclare(EXCHANGE_NAME, "direct");

        // 创建队列，随机分配一个队列名称，并绑定到 "xiaoyu" 路由键
        String queueName = "xiaoyu_queue";
        // 声明队列，设置队列为持久化的，非独占的，非自动删除的
        channel.queueDeclare(queueName, true, false, false, null);
        // 将队列绑定到指定的交换机上，并指定绑定的路由键为 "xiaoyu"
        channel.queueBind(queueName, EXCHANGE_NAME, "xiaoyu");

        // 创建队列，随机分配一个队列名称，并绑定到 "xiaopi" 路由键
        String queueName2 = "xiaopi_queue";
        channel.queueDeclare(queueName2, true, false, false, null);
        // 将队列绑定到指定的交换机上，并指定绑定的路由键为 "xiaopi"
        channel.queueBind(queueName2, EXCHANGE_NAME, "xiaopi");
    	// 打印等待消息的提示信息
        System.out.println(" [*] Waiting for messages. To exit press CTRL+C");

        
        // 创建一个 DeliverCallback 实例来处理接收到的消息（xiaoyu）
        DeliverCallback xiaoyuDeliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), "UTF-8");
            System.out.println(" [xiaoyu] Received '" +
                    delivery.getEnvelope().getRoutingKey() + "':'" + message + "'");
        };
        // 创建一个 DeliverCallback 实例来处理接收到的消息（xiaopi）
        DeliverCallback xiaopiDeliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), "UTF-8");
            System.out.println(" [xiaopi] Received '" +
                    delivery.getEnvelope().getRoutingKey() + "':'" + message + "'");
        };
        
        
    	 // 开始消费队列中的消息（xiaoyu），设置自动确认消息已被消费
        channel.basicConsume(queueName, true, xiaoyuDeliverCallback, consumerTag -> {
        });
        // 开始消费队列中的消息（xiaopi），设置自动确认消息已被消费
        channel.basicConsume(queueName2, true, xiaopiDeliverCallback, consumerTag -> {
        });
    }
}
```





3. topic 交换机

- 特点：消息会根据一个 **模糊的** 路由键转发到指定的队列
- 场景：特定的一类消息可以交给特定的一类系统（程序）来处理
- 绑定关系：可以模糊匹配多个绑定
- - *：匹配一个单词，比如 *.orange，那么 a.orange、b.orange 都能匹配
  - \#：匹配 0 个或多个单词，比如 a.#，那么 a.a、a.b、a.a.a 都能匹配



（发送端）只需要指定交换机类型为 topic

```Java
channel.exchangeDeclare(EXCHANGE_NAME, "topic");
```



（接收端）只需要指定匹配格式即可

```Java
    channel.queueBind(queueName, EXCHANGE_NAME, "#.前端.#");
    channel.queueBind(queueName2, EXCHANGE_NAME, "#.后端.#");
    channel.queueBind(queueName3, EXCHANGE_NAME, "#.产品.#");
```





4. Headers 交换机

类似于主题和直接交换机，⁠可以根据消息的 headers（头部信息）来确定消息应发送到哪个队列。由于性能较差且相对‍复杂，一般情况下并不推荐使用这种方式。





# 消息过期

参数设置：（将时效性的参数装入到 `args`，然后在创建队列的时候带上这个 `args`）

```Java
Map<String, Object> args = new HashMap<String, Object>();
args.put("x-message-ttl", 60000);
channel.queueDeclare("myqueue", false, false, false, args);
```





# 死信队列

为了保证消息的可靠性，比如每条消息都消费成功，需要提供一个容错机制

- **死信：**指过期的消息、被拒收的消息、消息队列已满以及处理失败的消息的统称。
- **死信队列：**专门用来处理死信的队列 (实际上是一个普通的队列，但被专门用来接收并处理死信消息。可以将它理解为名为"死信队列"的队列)。
- **死信交换机：**用于将死信消息转发到死信队列的交换机，也可以设置路由绑定来确定消息的路由规则 (是一个普通的交换机，只是被专门用来将消息发送到死信队列。可以将其理解为名为"死信交换机"的交换机)。



<img src="./智能BI.assets/屏幕截图 2025-07-27 170022.png" style="zoom:50%;" />



实现：（发送端，创建死信交换机和普通交换机）

```Java
public class DlxDirectProducer {
	// 定义死信交换机名称为"dlx-direct-exchange"
    private static final String DEAD_EXCHANGE_NAME = "dlx-direct-exchange";
    // 定义本来的业务交换机名称为"direct2-exchange"
    private static final String WORK_EXCHANGE_NAME = "direct2-exchange";

    public static void main(String[] argv) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {
            // 声明死信交换机
            channel.exchangeDeclare(DEAD_EXCHANGE_NAME, "direct");

            // 创建老板的死信队列，随机分配一个队列名称
            String queueName = "laoban_dlx_queue";
            channel.queueDeclare(queueName, true, false, false, null);
            channel.queueBind(queueName, DEAD_EXCHANGE_NAME, "laoban");
        	// 创建外包的死信队列，随机分配一个队列名称
            String queueName2 = "waibao_dlx_queue";
            channel.queueDeclare(queueName2, true, false, false, null);
            channel.queueBind(queueName2, DEAD_EXCHANGE_NAME, "waibao");
            
            
        	// 创建用于处理老板死信队列消息的回调函数，当接收到消息时，拒绝消息并打印消息内容
            DeliverCallback laobanDeliverCallback = (consumerTag, delivery) -> {
                String message = new String(delivery.getBody(), "UTF-8");
                // 拒绝消息，并且不要重新将消息放回队列，只拒绝当前消息
                channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false);
                System.out.println(" [laoban] Received '" +
                        delivery.getEnvelope().getRoutingKey() + "':'" + message + "'");
            };
        	// 创建用于处理外包死信队列消息的回调函数，当接收到消息时，拒绝消息并打印消息内容
            DeliverCallback waibaoDeliverCallback = (consumerTag, delivery) -> {
                String message = new String(delivery.getBody(), "UTF-8");
                // 拒绝消息，并且不要重新将消息放回队列，只拒绝当前消息
                channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false);
                System.out.println(" [waibao] Received '" +
                        delivery.getEnvelope().getRoutingKey() + "':'" + message + "'");
            };
            
            
        	// 注册消费者，用于消费老板的死信队列，绑定回调函数
            channel.basicConsume(queueName, false, laobanDeliverCallback, consumerTag -> {
            });
            // 注册消费者，用于消费外包的死信队列，绑定回调函数
            channel.basicConsume(queueName2, false, waibaoDeliverCallback, consumerTag -> {
            });
            
            
        	// 创建一个Scanner对象用于从控制台读取用户输入
            Scanner scanner = new Scanner(System.in);
            // 进入循环，等待用户输入消息和路由键
            while (scanner.hasNext()) {
                String userInput = scanner.nextLine();
                String[] strings = userInput.split(" ");
                if (strings.length < 1) {
                    continue;
                }
                String message = strings[0];
                String routingKey = strings[1];
            	// 发布消息到业务交换机，带上指定的路由键
                channel.basicPublish(WORK_EXCHANGE_NAME, routingKey, null, message.getBytes("UTF-8"));
                // 打印发送的消息和路由键
                System.out.println(" [x] Sent '" + message + " with routing:" + routingKey + "'");
            }

        }
    }
    //..
}
```



（接收端，通过指定参数来把普通队列和死信交换机做绑定，并且和普通交换机也要做绑定）

```Java
// 指定死信队列参数
Map<String, Object> args = new HashMap<>();
// 要绑定到哪个交换机
args.put("x-dead-letter-exchange", DEAD_EXCHANGE_NAME);
// 指定死信要转发到哪个死信队列
args.put("x-dead-letter-routing-key", "waibao");

// 创建队列，随机分配一个队列名称
String queueName = "xiaodog_queue";
channel.queueDeclare(queueName, true, false, false, args);  // 创建队列的时候指定参数，就是和死信交换机做绑定，消息拒绝了，就发给了死信交换机。
channel.queueBind(queueName, EXCHANGE_NAME, "xiaodog");  // 和普通交换机做绑定
```







# SpringBoot集成RabbitMq

1. 引入依赖（注意要和项目中的 `SpringBoot` 版本一致！！！）

```xml
<!-- https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-amqp -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
    <version>2.7.2</version>
</dependency>
```

2. 在 `application.yml` 文件中写配置文件：

```yml
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
```

3. 生产者代码

```Java
@Component
public class MyMessageProducer {

    @Resource
    private RabbitTemplate rabbitTemplate;

    // 指定发送到哪个交换机，路由键，消息
    public void sendMessage(String exchange, String routingKey, String message){
        rabbitTemplate.convertAndSend(exchange, routingKey, message);
    }
}
```

4. 消费者代码

```Java
@Component
@Slf4j
public class MyMessageConsumer {

    // ackMode = "MANUAL" 指定接收类型为人工接收，queues中指定监听哪个消息队列
    @SneakyThrows
    @RabbitListener(queues = {"code_queue"}, ackMode = "MANUAL")
    public void receiveMessage(String message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag){
        log.info("receiveMessage message = {}", message);
        channel.basicAck(deliveryTag, false);  // 一次确认一条消息
    }
}
```

5. 创建测试用到的交换机和队列：

```Java
    public static void main(String[] args) {
        try {
            // 创建连接工厂
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost("localhost");
            // 创建连接
            Connection connection = factory.newConnection();
            // 创建通道
            Channel channel = connection.createChannel();
            
            // 定义交换机的名称为"code_exchange"
            String EXCHANGE_NAME = "code_exchange";
            // 声明交换机，指定交换机类型为 direct
            channel.exchangeDeclare(EXCHANGE_NAME, "direct");

            // 创建队列，随机分配一个队列名称
            String queueName = "code_queue";
            // 声明队列，设置队列持久化、非独占、非自动删除，并传入额外的参数为 null
            channel.queueDeclare(queueName, true, false, false, null);
            // 将队列绑定到交换机，指定路由键为 "my_routingKey"
            channel.queueBind(queueName, EXCHANGE_NAME, "my_routingKey");
            
        } catch (Exception e) {
            // 异常处理
        }
    }
```



- ***测试：***

```java
@Resource
private MyMessageProducer myMessageProducer;

@Test
void sendMessage(){
	myMessageProducer.sendMessage("code_exchange", "my_routingKey", "你好");
}
```









# BI项目改造

以前是把任务提交到线程池，然后在线程池提交中编写处理程序的代码，线程池内排队。

如果程序中断了，任务就丢失了。



改造后的流程：

1. 把任务提交改为向队列发送消息
2. 写一个专门的接收消息程序，处理任务
3. 如果程序中断了，消息未被确认，还会重发吗
4. 现在，消息全部集中发送到消息队列，可以部署多个后端，都从同一个地方取任务，从而实现了分布式负载均衡







增加容错机制：

实现：

1. 创建死信交换机
2. 给失败之后需要容错处理的队列绑定死信交换机
3. 可以给要容错的队列指定死信之后的转发规则，死信之后应该再转发到哪个死信队列







