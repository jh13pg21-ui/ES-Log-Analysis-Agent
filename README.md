# ES 日志智能分析 Agent

基于 **Spring AI + DDD 六层架构** 构建的智能日志分析平台，将 MCP Tool、Prompt、RAG 等能力抽象为可配置组件，通过数据库持久化实现动态编排，按需组装不同场景的 Agent。

> 2025.12 - 2026.02

## 技术栈

Spring AI（RAG / MCP / Advisor）/ Spring Boot 3.4 / MyBatis / MySQL / pgVector

## 模块架构

```text
├── ai-agent-station-study-api               RPC 接口契约层
├── ai-agent-station-study-types             共享层（枚举 / 常量 / 异常）
├── ai-agent-station-study-domain            领域层（DDD 核心）
│   ├── agent/service/execute/auto/step/       五阶段 ReAct Workflow
│   ├── agent/service/armory/                  Agent 动态装配工厂
│   │   ├── AiClientToolMcpNode                MCP Tool（SSE / STDIO）
│   │   ├── RagAnswerAdvisor                  Agentic RAG 检索增强
│   │   ├── MemoryAdvisor                     长期记忆（跨会话向量检索）
│   │   └── SummaryBufferChatMemory            短期记忆（滑动窗口 + LLM 摘要压缩）
│   └── agent/service/rag/                    知识库管理（上传 / 分块 / 索引）
├── ai-agent-station-study-infrastructure     基础设施层
│   ├── repository                            聚合仓储（11 张配置表 + pgVector）
│   ├── dao                                   MyBatis DAO
│   └── elasticsearch                         ES 集成
├── ai-agent-station-study-trigger            触发器层
│   ├── http/AiAgentController                SSE 流式对话接口
│   └── http/RagController                    知识库管理接口
└── ai-agent-station-study-app                启动 & 全局配置
    └── config/AiAgentAutoConfiguration       启动时自动装配 Agent 实例
```

## 核心亮点

### 1. Agentic Workflow 五阶段执行链路

基于规则树思想，设计 ReAct 五阶段执行链路：**意图识别 → 任务分析 → 精准执行 → 质量监督 → 总结报告**。大模型驱动节点间状态流转，质量监督节点可回路至任务分析重新执行，以 `maxStep` 控制最大循环次数。支持 CHAT / KNOWLEDGE / TOOL / MIXED 四种意图分类，混合意图下同时启用 RAG 与 MCP Tool。

### 2. Agentic RAG 检索闭环

- **双路分块策略**：TokenTextSplitter 固定分块 + SemanticTextSplitter 语义段落分块
- **双路召回**：pgVector 向量相似度 + PostgreSQL BM25 全文检索（`ts_rank`）
- **RRF 融合重排**（k=60）：合并向量与关键词结果集
- **LLM 文档相关性评估**：评分低于 0.6 阈值的文档被过滤
- **Query Rewriting**：优质文档不足时自动改写查询，最多 3 轮迭代
- **降级兜底**：最终轮次直接使用 RRF 原始结果

### 3. 长短期记忆体系

- **短期记忆（SummaryBufferChatMemory）**：滑动窗口保留最近原始消息，超出上限时由 LLM 将历史对话压缩为摘要注入 SystemMessage，替代硬截断方案
- **长期记忆（MemoryAdvisor）**：每 N 轮对话异步压缩为摘要存入 pgVector，新会话通过向量检索召回相关历史记忆注入 Prompt，实现跨会话持续记忆

### 4. ELK 日志闭环分析链路

集成 ES MCP Server（SSE 传输），实现「需求解析 → ELK 检索执行 → 结果质量监督 → 报告总结」闭环设计。以「最大循环次数 + 分析结果完整性」双条件控制流程，动态判断是否重新检索，应对非标准日志排查任务。

### 5. Armory 动态装配机制

Prompt、MCP Tool、RAG Advisor、Memory 等能力均为数据库可配置组件。启动时通过规则树策略链自动装配 ChatClient Bean，实现 Agent 按需动态组装，无需重启服务即可更换模型、调整 Prompt、增减工具。

## 快速开始

```bash
# 克隆
git clone <your-repo-url>
cd ai-agent-station-study

# 编译
mvn clean package -DskipTests

# 启动（需 MySQL + PostgreSQL pgVector + ES MCP Server）
cd ai-agent-station-study-app
mvn spring-boot:run -Dspring.profiles.active=dev
```

## License

Apache License, Version 2.0
