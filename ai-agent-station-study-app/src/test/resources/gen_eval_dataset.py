#!/usr/bin/env python3
"""RAG 评估数据集生成器 — 从模板批量生成 200+ 用例"""
import json, itertools, random

random.seed(42)

# ============================================================
# 模板定义： (query_template, [expected_keywords], relevant_docs)
# {kw} 会被替换为同义词变体
# ============================================================

# ── Doc 01: 字段与实体速查 ──
synonyms = {
    "字段": ["字段", "域", "属性", "列", "field"],
    "有哪些": ["有哪些", "包含哪些", "记录了哪些", "支持哪些", "都有什么"],
    "怎么查": ["怎么查", "怎么检索", "如何搜索", "用什么条件查", "怎么筛选"],
    "是什么": ["是什么", "是什么含义", "什么意思", "指什么", "代表什么"],
    "在哪里": ["在哪里", "在哪个索引", "存在哪", "写到哪了"],
    "多少": ["多少", "几条", "多少次", "多少个"],
    "能不能": ["能不能", "是否可以", "支持吗", "可行吗", "能做到吗"],
}

doc01_templates = [
    # 索引命名
    ("{有哪些} 索引？ES 索引 {是什么} 格式", ["ai-agent-station-logs", "YYYY.MM.DD", "Index Pattern", "ai-agent-station-logs-*"], "doc_01"),
    ("日志索引怎么命名？{在哪里} 存储", ["ai-agent-station-logs", "YYYY.MM.DD", "按天滚动"], "doc_01"),
    ("Kibana 配 Index Pattern 用什么", ["ai-agent-station-logs-*", "Index Pattern"], "doc_01"),
    ("每天日志存在哪个索引里", ["ai-agent-station-logs", "YYYY.MM.DD", "按天"], "doc_01"),
    ("索引前缀 {是什么}", ["ai-agent-station-logs", "前缀"], "doc_01"),

    # tags 分类
    ("日志有{哪些}类型？tags {字段} {有哪些}值", ["access", "business", "security", "slow_query", "exception", "scheduled_task", "rate_limit", "attack"], "doc_01"),
    ("{有哪些} 日志分类？怎么用 tags 区分", ["tags", "access", "business", "security", "slow_query", "exception", "scheduled_task"], "doc_01"),
    ("七种日志分别对应什么 tags 值", ["tags", "access", "business", "rate_limit", "attack", "slow_query", "exception", "scheduled_task"], "doc_01"),
    ("security 类型的日志包含哪两种子类型", ["security", "rate_limit", "attack"], "doc_01"),
    ("每种日志每天写入{多少}条", ["500", "200", "100", "50", "80", "40", "30"], "doc_01"),

    # 通用字段
    ("所有日志共用的{字段} {有哪些}", ["@timestamp", "log.level", "log.logger", "trace.id", "tags"], "doc_01"),
    ("trace.id {是什么}格式", ["trace.id", "UUID"], "doc_01"),
    ("log.level 有哪几种取值", ["log.level", "INFO", "WARN", "ERROR"], "doc_01"),
    ("怎么通过 trace.id 追踪一次完整请求", ["trace.id", "sort", "asc"], "doc_01"),
    ("message {字段}和 error.message 有什么区别", ["message", "error.message", "error.stack_trace"], "doc_01"),

    # 访问日志字段
    ("访问日志记录哪些 HTTP 请求信息", ["http.request.method", "http.request.endpoint", "http.response.status_code", "http.response.time_ms"], "doc_01"),
    ("http.response.time_ms 取值范围是{多少}", ["http.response.time_ms", "5", "3000"], "doc_01"),
    ("访问日志里有哪些 API 端点", ["/api/v1/agent/auto_agent", "/api/v1/order/create", "/api/v1/payment/pay"], "doc_01"),
    ("http.request.method 支持哪些值", ["GET", "POST", "PUT", "DELETE", "http.request.method"], "doc_01"),
    ("响应状态码包括哪些", ["status_code", "200", "201", "400", "401", "403", "404", "429", "500", "502", "503"], "doc_01"),
    ("geo.city 记录了哪些城市", ["geo.city", "北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "西安", "南京", "重庆"], "doc_01"),
    ("geo.isp 运营商有哪些", ["geo.isp", "中国电信", "中国联通", "中国移动", "教育网"], "doc_01"),
    ("{怎么查}某个端点的 P99 延迟", ["http.request.endpoint", "http.response.time_ms", "percentiles", "p99"], "doc_01"),
    ("访问日志的 user_agent {字段} 记录了哪些信息", ["user_agent", "iPhone", "Android", "Windows NT", "Macintosh", "okhttp"], "doc_01"),
    ("body_size {字段} 的单位和范围", ["body_size", "100", "50000"], "doc_01"),

    # 业务日志字段
    ("业务日志记录了哪些 action", ["business.action", "用户注册", "创建订单", "支付成功", "退款到账", "加入拼团"], "doc_01"),
    ("business.result 有哪几种取值", ["business.result", "SUCCESS", "FAIL", "PENDING"], "doc_01"),
    ("业务日志里 channel {字段} {有哪些}值", ["business.channel", "APP", "H5", "PC", "MINIPROGRAM"], "doc_01"),
    ("order_no 的格式 {是什么}", ["order_no", "ORD", "年月日", "数字"], "doc_01"),
    ("business.amount 单位 {是什么}", ["business.amount", "分"], "doc_01"),
    ("用户 VIP 等级的范围", ["user.vip_level", "0", "5"], "doc_01"),
    ("业务日志里 product_id 有哪些可选值", ["product_id", "product_001", "product_002", "product_008"], "doc_01"),
    ("{怎么查}支付成功的总金额", ["支付成功", "sum", "business.amount"], "doc_01"),
    ("业务日志的 user.id 范围", ["user.id", "user_10001", "user_30002"], "doc_01"),
    ("如何按 business.action 看成功率趋势", ["business.action", "business.result", "date_histogram"], "doc_01"),

    # 限流/黑名单字段
    ("限流类型 rate_limit.type 有哪些值", ["rate_limit.type", "rate_limit", "frequency_limit", "ip_blacklist", "daily_limit", "user_blacklist", "device_blacklist"], "doc_01"),
    ("rate_limit.request_count 和 limit_threshold 的关系", ["rate_limit.request_count", "rate_limit.limit_threshold", "5", "200", "10", "50"], "doc_01"),
    ("risk.level 分哪四个等级", ["risk.level", "CRITICAL", "HIGH", "MEDIUM", "LOW"], "doc_01"),
    ("risk.score 的分数范围", ["risk.score", "0", "100"], "doc_01"),
    ("限流日志的 HTTP 响应码固定为{多少}", ["429", "status_code"], "doc_01"),
    ("rate_limit.window_seconds 窗口多长", ["rate_limit.window_seconds", "10", "60"], "doc_01"),
    ("rate_limit.reason 有哪些原因", ["rate_limit.reason", "访问频率过高", "恶意刷单", "异常IP访问", "超过每日限制", "黑名单用户", "设备指纹异常"], "doc_01"),
    ("rate_limit.retry_count 重试次数范围", ["rate_limit.retry_count", "0", "5"], "doc_01"),
    ("限流日志和攻击日志怎么通过 tags 区分", ["tags", "rate_limit", "attack", "security"], "doc_01"),
    ("{怎么查}某用户被限流了多少次", ["rate_limit", "user.id"], "doc_01"),

    # 安全攻击字段
    ("安全攻击类型 attack_type {有哪些}", ["attack_type", "sql_injection", "xss_attack", "csrf_blocked", "brute_force", "login_fail", "path_traversal", "file_upload_abuse"], "doc_01"),
    ("security.blocked {字段} 含义", ["security.blocked", "blocked", "拦截", "true", "false"], "doc_01"),
    ("攻击检测引擎 detection_engine 有哪些", ["detection_engine", "regex", "rule_engine", "waf_rule"], "doc_01"),
    ("security.payload {字段} 存了什么", ["security.payload", "payload", "SQL", "script", "passwd"], "doc_01"),
    ("攻击日志里 endpoint {字段} 表示什么", ["security.endpoint", "endpoint"], "doc_01"),
    ("sql_injection 攻击的 payload 长什么样", ["sql_injection", "payload", "OR 1=1", "union select", "DROP TABLE"], "doc_01"),
    ("xss_attack 的 payload 特征", ["xss_attack", "script", "onerror", "img"], "doc_01"),
    ("brute_force {是什么} 攻击，怎么检测", ["brute_force", "密码连续错误", "5次"], "doc_01"),
    ("login_fail 和 brute_force 的区别", ["login_fail", "brute_force", "异地异常登录"], "doc_01"),
    ("{怎么查}攻击穿透（blocked=false）的事件", ["blocked", "false", "CRITICAL"], "doc_01"),

    # 慢查询字段
    ("慢查询日志记录了哪些数据库表", ["slow_query.table", "ai_client", "orders", "payments", "products", "group_buy_records", "rag_document"], "doc_01"),
    ("slow_query.duration_ms 多{少}算慢", ["slow_query.duration_ms", "500", "15000"], "doc_01"),
    ("rows_examined {字段} 扫描行数范围", ["slow_query.rows_examined", "1000", "500000"], "doc_01"),
    ("慢查询原因 slow_query.reason 有哪几种", ["slow_query.reason", "全表扫描", "索引缺失", "JOIN过多", "锁等待", "数据量大", "未命中缓存"], "doc_01"),
    ("{怎么查}慢查询最多的表", ["slow_query.table", "terms", "aggs"], "doc_01"),
    ("slow_query.database 记录了哪个数据库", ["slow_query.database", "ai-agent-station-study"], "doc_01"),
    ("duration_ms 超过{多少}时日志级别变为 ERROR", ["duration_ms", "5000", "ERROR", "log.level"], "doc_01"),
    ("connection_id {字段} 范围", ["slow_query.connection_id", "1", "100"], "doc_01"),
    ("慢查询日志的 SQL 完整语句存在哪个{字段}", ["slow_query.sql", "sql"], "doc_01"),
    ("{怎么查}全表扫描的慢查询", ["全表扫描", "slow_query.reason", "text"], "doc_01"),

    # 异常日志字段
    ("异常日志 error.type 记录了哪些类型", ["error.type", "NullPointerException", "SQLException", "TimeoutException", "OutOfMemoryError", "JsonMappingException"], "doc_01"),
    ("error.stack_trace {字段} 存了什么", ["error.stack_trace", "stack_trace", "堆栈"], "doc_01"),
    ("哪些服务类会记录异常日志", ["AiAgentController", "RagController", "AutoAgentExecuteStrategy", "RagRepository", "OrderService", "PaymentService"], "doc_01"),
    ("NullPointerException 一般在哪个服务出现", ["NullPointerException", "服务", "Controller"], "doc_01"),
    ("SQLException 和慢查询之间有什么关联", ["SQLException", "slow_query", "数据库"], "doc_01"),
    ("TimeoutException 一般是因为什么", ["TimeoutException", "超时", "下游服务"], "doc_01"),
    ("OutOfMemoryError 出现意味着什么", ["OutOfMemoryError", "JVM", "内存"], "doc_01"),
    ("{怎么查}某个服务的异常分布", ["error.type", "log.logger", "terms"], "doc_01"),
    ("JsonMappingException {是什么} 原因", ["JsonMappingException", "JSON", "序列化"], "doc_01"),
    ("ResourceAccessException 和 TimeoutException 的区别", ["ResourceAccessException", "TimeoutException", "网络", "超时"], "doc_01"),

    # 后台任务字段
    ("后台任务 scheduled_task.name 有哪些", ["scheduled_task.name", "OrderExpireJob", "DailyReportJob", "DataSyncJob", "CacheRefreshJob", "LogCleanupJob", "PaymentReconcileJob"], "doc_01"),
    ("scheduled_task.action 有哪几种状态", ["scheduled_task.action", "执行开始", "处理中", "执行完成", "执行失败"], "doc_01"),
    ("定时任务执行耗时 duration_ms 范围", ["scheduled_task.duration_ms", "100", "600000"], "doc_01"),
    ("cron {字段} 记录了哪些表达式", ["scheduled_task.cron", "cron"], "doc_01"),
    ("{怎么查}某个任务最近是否有失败", ["scheduled_task", "执行失败", "result"], "doc_01"),
    ("OrderExpireJob {是什么}任务", ["OrderExpireJob", "scheduled_task"], "doc_01"),
    ("PaymentReconcileJob 执行频率", ["PaymentReconcileJob", "cron"], "doc_01"),
    ("CacheRefreshJob 失败了怎么排查", ["CacheRefreshJob", "执行失败", "exception"], "doc_01"),
    ("scheduled_task.result {字段} 包含什么信息", ["scheduled_task.result", "处理", "条", "失败"], "doc_01"),
    ("{怎么查}所有定时任务的平均耗时", ["scheduled_task", "avg", "duration_ms"], "doc_01"),
]

# ── Doc 02: 项目配置与安全阈值 ──
doc02_templates = [
    ("ELK 部署在哪个服务器，IP {是什么}", ["47.94.17.237", "9200", "5601", "elasticsearch", "kibana"], "doc_02"),
    ("ES 版本 {是什么}，用的什么镜像", ["7.17.28", "aliyuncs.com", "elasticsearch", "xfg-studio"], "doc_02"),
    ("ES 集群名称 {是什么}", ["docker-cluster", "cluster.name"], "doc_02"),
    ("Kibana 端口 {是多少}", ["5601", "kibana"], "doc_02"),
    ("Kibana 报了 500 错误怎么修", ["DELETE", ".kibana", "docker restart kibana"], "doc_02"),
    ("ELK 的 docker-compose 文件叫什么", ["docker-compose-elk-aliyun.yml"], "doc_02"),

    ("CRITICAL 风险等级对应{多少}分", ["CRITICAL", "80", "100", "ERROR"], "doc_02"),
    ("HIGH 风险等级对应{多少}分", ["HIGH", "60", "79", "ERROR"], "doc_02"),
    ("MEDIUM 风险等级对应{多少}分，日志级别 {是什么}", ["MEDIUM", "40", "59", "WARN"], "doc_02"),
    ("LOW 风险等级需要告警吗", ["LOW", "0", "39", "INFO", "仅记录"], "doc_02"),
    ("risk.level 和 log.level 的映射关系", ["CRITICAL", "ERROR", "HIGH", "ERROR", "MEDIUM", "WARN", "LOW", "INFO"], "doc_02"),

    ("ip_blacklist 限流误判风险高不高", ["ip_blacklist", "误判", "企业出口IP", "CDN"], "doc_02"),
    ("user_blacklist 黑名单误加怎么办", ["user_blacklist", "误判", "审计"], "doc_02"),
    ("device_blacklist 为什么会误判", ["device_blacklist", "误判", "多人共用设备"], "doc_02"),
    ("rate_limit 和 frequency_limit 有什么区别", ["rate_limit", "frequency_limit", "瞬时", "长窗口"], "doc_02"),
    ("daily_limit 一般用在什么场景", ["daily_limit", "每日限额", "新用户活动"], "doc_02"),

    ("数据库有哪些核心表", ["ai_client", "ai_client_config", "orders", "payments", "users", "products", "group_buy_records", "ai_agent", "rag_document"], "doc_02"),
    ("ai_client 表容易出现慢查询吗", ["ai_client", "读多写少", "Redis", "缓存"], "doc_02"),
    ("orders 表为什么慢", ["orders", "数据增长快", "user_id", "create_time", "联合索引"], "doc_02"),
    ("payments 表慢查询常见原因", ["payments", "状态字段", "索引"], "doc_02"),
    ("group_buy_records 表怎么优化", ["group_buy_records", "product_id", "create_time", "联合索引", "物化视图"], "doc_02"),
    ("ai_client_config 表是干什么的", ["ai_client_config", "关联配置", "多对多"], "doc_02"),
    ("ai_agent_flow_config 表按什么字段查", ["ai_agent_flow_config", "agent_id", "索引"], "doc_02"),
    ("rag_document 表存了什么", ["rag_document", "文档", "agent_id", "status"], "doc_02"),
    ("ai_client_model 表为什么慢", ["ai_client_model", "model", "api", "JOIN", "三级关联"], "doc_02"),
    ("products 表慢查询优化建议", ["products", "聚合查询", "count", "group by"], "doc_02"),

    ("MCP 工具怎么注册的", ["MCP", "ai_client_tool_mcp", "STDIO", "elasticsearch-mcp-server"], "doc_02"),
    ("elasticsearch-mcp-server 配置在哪张表", ["ai_client_tool_mcp", "mcp_id", "5007", "npx"], "doc_02"),
    ("MCP 工具的 ES_HOST 指向哪里", ["ES_HOST", "47.93.200.142", "9200"], "doc_02"),
    ("MCP 工具的 command {是什么}", ["npx", "@awesome-ai/elasticsearch-mcp"], "doc_02"),

    ("限流 QPS 正常值{是多少}，什么时候告警", ["QPS", "50", "100", "持续5分钟"], "doc_02"),
    ("CRITICAL 占比{多少}算异常", ["CRITICAL", "20%", "10分钟"], "doc_02"),
    ("单 IP 每小时{多少}次限流算异常", ["单IP", "50", "小时"], "doc_02"),
    ("P99 延迟超过{多少}需要关注", ["P99", "3000ms", "1000ms"], "doc_02"),
    ("5xx 错误率{多少}触发告警", ["5xx", "错误率", "5%"], "doc_02"),
    ("慢查询每小时超过{多少}条算异常", ["慢查询", "50", "小时"], "doc_02"),
    ("异常日志每小时超过{多少}条需要关注", ["异常", "30", "小时"], "doc_02"),
    ("单用户每小时限流{多少}次算异常", ["单用户", "20", "小时"], "doc_02"),
    ("慢查询 duration_ms 超过{多少}算严重", ["duration_ms", "10000", "P1", "5000", "P2"], "doc_02"),
    ("rows_examined 超过{多少}算严重", ["rows_examined", "100000", "P1", "50000", "P2"], "doc_02"),
]

# ── Doc 03: ES 查询模板 ──
doc03_templates = [
    ("{怎么查}各类日志的数量分布", ["tags", "terms", "aggs", "date_histogram"], "doc_03"),
    ("{怎么查}每个端点的错误率排行", ["endpoint", "error_rate", "bucket_script", "500"], "doc_03"),
    ("{怎么查}支付成功率的时间趋势", ["business.action", "支付", "per_hour", "date_histogram"], "doc_03"),
    ("{怎么查}今天的 GMV", ["GMV", "支付成功", "sum", "business.amount", "now/d"], "doc_03"),
    ("{怎么查}哪些 IP 既有攻击行为又触发限流", ["remote_ip", "rate_limit", "attack", "by_ip", "高危"], "doc_03"),
    ("{怎么查}攻击穿透（blocked=false 且 CRITICAL）", ["blocked", "false", "CRITICAL", "穿透"], "doc_03"),
    ("{怎么查}每张表的慢查询数量和平均耗时", ["slow_query.table", "by_table", "avg_dur", "by_reason"], "doc_03"),
    ("{怎么查}异常按类型和服务的分布", ["error.type", "log.logger", "by_type", "by_service"], "doc_03"),
    ("{怎么查}哪些定时任务失败了", ["scheduled_task", "失败", "by_job"], "doc_03"),
    ("{怎么查}某个 trace.id 的完整请求链路", ["trace.id", "sort", "asc"], "doc_03"),
    ("{怎么查}故障时间窗口内的所有日志", ["filter", "range", "gte", "lte", "@timestamp"], "doc_03"),
    ("{怎么查}慢查询和异常在同一时间窗口是否同时升高", ["slow_query", "exception", "per_hour", "date_histogram"], "doc_03"),
    ("{怎么查}一次攻击事件的影响范围", ["attack", "rate_limit", "exception", "per_minute", "http_errors"], "doc_03"),
    ("{怎么查}某个 IP 的攻击历史", ["remote_ip", "attack", "rate_limit", "bool"], "doc_03"),
    ("{怎么查}支付失败对应的异常日志", ["支付失败", "exception", "payment"], "doc_03"),
    ("query 语法：{怎么查}限流日志 CRITICAL 级别", ["rate_limit", "CRITICAL", "bool", "must"], "doc_03"),
    ("query 语法：{怎么查}最近 24 小时 ERROR 日志", ["ERROR", "now-24h", "range"], "doc_03"),
    ("query 语法：{怎么查}北京地区被限流的用户", ["geo.city", "北京", "rate_limit", "terms"], "doc_03"),
    ("{怎么查}每个渠道的支付成功率", ["business.channel", "支付", "by_result", "SUCCESS"], "doc_03"),
    ("{怎么查}每个 VIP 等级用户的客单价", ["vip_level", "business.amount", "avg_amount"], "doc_03"),
    ("{怎么查}某段时间内的 QPS 趋势", ["date_histogram", "per_hour", "access"], "doc_03"),
    ("{怎么查}访问日志的 P99 延迟变化", ["http.response.time_ms", "percentiles", "p99", "access"], "doc_03"),
    ("{怎么查}某个端点的 QPS 和错误分布", ["endpoint", "qps", "status_dist", "p99"], "doc_03"),
    ("{怎么查}攻击源 IP Top 20", ["remote_ip", "attack", "by_ip", "top_ips"], "doc_03"),
    ("{怎么查}数据连接池是否耗尽", ["Connection pool exhausted", "Time", "exception"], "doc_03"),

    # 模板变体（同义查询）
    ("统计每种日志各有多少条", ["tags", "terms", "aggs"], "doc_03"),
    ("给一个查询：找错误率最高的 10 个接口", ["endpoint", "error_rate", "500"], "doc_03"),
    ("过去一小时支付成功率下降了多少", ["支付", "per_hour", "business.action"], "doc_03"),
    ("算一下今天到目前为止的交易总额", ["GMV", "sum", "business.amount", "now/d"], "doc_03"),
    ("帮我查：来自同一个 IP 的限流和攻击记录", ["remote_ip", "rate_limit", "attack", "高危"], "doc_03"),
    ("有没有被攻击了但没拦截住的请求", ["blocked", "false", "CRITICAL", "穿透"], "doc_03"),
    ("哪张表慢查询最多，平均耗时多长", ["slow_query.table", "avg_dur", "terms"], "doc_03"),
    ("哪些异常类型出现最频繁", ["error.type", "by_type", "terms"], "doc_03"),
    ("最近一周有哪些定时任务失败了", ["scheduled_task", "失败", "一周"], "doc_03"),
    ("用 traceId 查整个请求链路怎么查", ["trace.id", "sort"], "doc_03"),
    ("查一下故障时间窗口内发生了什么", ["filter", "range", "@timestamp", "gte", "lte"], "doc_03"),
    ("慢查询爆发的时间点是不是也有异常爆发", ["slow_query", "exception", "per_hour"], "doc_03"),
    ("这次攻击影响了哪些方面", ["attack", "rate_limit", "impact", "http_errors"], "doc_03"),
    ("帮我查 192.168.1.100 这个 IP 有没有攻击行为", ["remote_ip", "attack", "rate_limit"], "doc_03"),
    ("支付接口的异常报什么错", ["payment", "exception", "error.type"], "doc_03"),
]

# ── 跨文档混合查询 ──
mixed_templates = [
    ("安全攻击有多少种类型？每种对应的 risk.level 是什么", ["attack_type", "sql_injection", "risk.level", "CRITICAL", "HIGH"], "doc_01,doc_02"),
    ("慢查询最多的表是哪个？这个表在数据库里是干什么的", ["slow_query.table", "orders", "支付表", "数据增长快"], "doc_01,doc_02"),
    ("ES 里怎么查某个表的所有慢查询", ["slow_query.table", "bool", "must", "term"], "doc_02,doc_03"),
    ("限流阈值设多少合适？查一下最近被限流的 QPS", ["limit_threshold", "QPS", "rate_limit", "告警"], "doc_02,doc_03"),
    ("哪些 API 端点最容易被攻击？攻击类型分布", ["endpoint", "attack_type", "security.endpoint", "api/v1"], "doc_01,doc_03"),
    ("怎么判断系统整体健康度？需要查哪些指标", ["P99", "错误率", "慢查询", "限流", "健康度"], "doc_02,doc_03"),
    ("给我写一个查各城市限流分布的 ES 查询", ["geo.city", "rate_limit", "terms", "aggs"], "doc_01,doc_03"),
    ("如何关联一个用户的业务行为和安全事件", ["user.id", "business", "security", "bool"], "doc_01,doc_03"),
    ("ES 中 http.request.endpoint 有哪些取值？哪个被攻击最多", ["endpoint", "/api/v1/agent/auto_agent", "/api/v1/payment/pay", "security.endpoint"], "doc_01,doc_03"),
    ("列出所有 scheduled_task 的名称，并告诉哪个最容易失败", ["scheduled_task.name", "OrderExpireJob", "PaymentReconcileJob", "失败"], "doc_01,doc_03"),
    ("Elasticsearch 的地址是什么，怎么查 ES 集群健康状态", ["47.94.17.237", "9200", "_cluster/health"], "doc_02,doc_03"),
    ("哪些表有索引缺失的问题，对应的慢查询 SQL 怎么写", ["索引缺失", "slow_query.reason", "orders", "ai_client"], "doc_01,doc_02"),
    ("如果 P99 超过 3 秒，应该查哪些日志找原因", ["P99", "3000", "slow_query", "exception", "access"], "doc_01,doc_02,doc_03"),
    ("支付失败率突然升高，从哪些日志入手排查", ["支付失败", "business", "exception", "attack", "payment"], "doc_01,doc_02,doc_03"),
    ("被攻击后怎么评估影响范围？查哪些指标", ["attack", "http_errors", "exception", "rate_limit", "impact"], "doc_02,doc_03"),
    ("ELK 上怎么同时查出慢查询和对应时间段的异常日志", ["slow_query", "exception", "per_hour", "date_histogram", "bool"], "doc_01,doc_03"),
    ("frequency_limit 的误判风险高吗？怎么查看是不是误判", ["frequency_limit", "误判", "user_agent", "okhttp", "python-requests"], "doc_01,doc_02"),
    ("用户支付时 5xx 错误怎么排查？查哪些日志和字段", ["5xx", "payment", "access", "exception", "slow_query"], "doc_01,doc_02,doc_03"),
    ("想查最近一周各城市的安全事件分布，给个 ES 查询", ["geo.city", "security", "terms", "aggs", "date_histogram"], "doc_01,doc_03"),
    ("MCP 工具连的 ES 地址怎么查？能不能通过 MCP 执行查询", ["MCP", "ES_HOST", "elasticsearch-mcp-server", "ai_client_tool_mcp"], "doc_02,doc_03"),
    ("CRITICAL 级别限流集中在哪些接口，分别对应什么限流策略", ["CRITICAL", "endpoint", "rate_limit.type", "rate_limit"], "doc_01,doc_02,doc_03"),
    ("怎么监控整个系统的异常趋势？需要看哪些日志类型", ["异常", "exception", "slow_query", "access", "error_rate", "趋势"], "doc_01,doc_02"),
    ("数据库 orders 表的慢查询 SQL 长什么样，怎么优化", ["orders", "slow_query.sql", "rows_examined", "联合索引"], "doc_01,doc_02"),
    ("如果发现 sql_injection 攻击，应该同时查哪些关联日志", ["sql_injection", "attack", "access", "exception", "trace.id"], "doc_01,doc_02,doc_03"),
    ("给我一份系统周报，需要包含哪些维度的数据", ["周报", "QPS", "错误率", "支付成功", "GMV", "慢查询", "异常", "安全事件"], "doc_01,doc_02,doc_03"),
]


# ============================================================
# 生成用例
# ============================================================
def expand(templates):
    """展开模板中的 {synonym} 占位符，每种同义词组合生成一个用例"""
    cases = []
    for tmpl, keywords, docs in templates:
        # 找出模板中的所有占位符
        placeholders = []
        for kw, variants in synonyms.items():
            if "{" + kw + "}" in tmpl:
                placeholders.append((kw, variants))

        if not placeholders:
            cases.append({"query": tmpl, "expectedKeywords": keywords, "expectedDocIds": docs.split(","), "minRelevantDocs": 1})
            continue

        # 每个占位符随机选 1 个变体（最多生成 2 个变体/模板，避免膨胀）
        for _ in range(2):
            query = tmpl
            for kw, variants in placeholders:
                query = query.replace("{" + kw + "}", random.choice(variants))
            cases.append({"query": query, "expectedKeywords": keywords, "expectedDocIds": docs.split(","), "minRelevantDocs": 1})

    return cases


cases = []
cases.extend(expand(doc01_templates))
cases.extend(expand(doc02_templates))
cases.extend(expand(doc03_templates))
cases.extend(expand(mixed_templates))

# 去重
seen = set()
unique = []
for c in cases:
    if c["query"] not in seen:
        seen.add(c["query"])
        unique.append(c)

# 补齐到 200
while len(unique) < 200:
    src = random.choice(doc01_templates + doc02_templates + doc03_templates + mixed_templates)
    expanded = expand([src])
    for c in expanded:
        if c["query"] not in seen and len(unique) < 200:
            seen.add(c["query"])
            unique.append(c)

random.shuffle(unique)
unique = unique[:200]

# 统计覆盖
d01 = sum(1 for c in unique if "doc_01" in c["expectedDocIds"])
d02 = sum(1 for c in unique if "doc_02" in c["expectedDocIds"])
d03 = sum(1 for c in unique if "doc_03" in c["expectedDocIds"])
mixed = sum(1 for c in unique if len(c["expectedDocIds"]) > 1)

print(f"Generated {len(unique)} cases")
print(f"  doc_01: {d01}, doc_02: {d02}, doc_03: {d03}, mixed: {mixed}")

with open("rag-eval-dataset.json", "w", encoding="utf-8") as f:
    json.dump(unique, f, ensure_ascii=False, indent=2)
print("Written to rag-eval-dataset.json")
