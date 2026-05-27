package cn.bugstack.ai.domain.agent.service.execute.auto.step;

import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory.IntentResult;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Step0：意图识别节点
 * <p>
 * 在正式执行前用轻量 LLM 调用判断用户意图：
 * - 是否需要查询 RAG 知识库
 * - 是否需要调用 MCP 工具
 * 结果写入 DynamicContext.intentResult，供后续节点按需使用。
 */
@Slf4j
@Service
public class Step0IntentRecognitionNode extends AbstractExecuteSupport {

    /** 意图识别 Prompt 模板，%s 占位符为用户输入 */
    private static final String INTENT_PROMPT_TEMPLATE = """
            你是一个意图分析助手。请分析用户的问题，判断处理该问题需要哪些资源。

            可用资源说明：
            - RAG知识库：存储了项目文档、业务知识、专业资料等内部知识
            - MCP工具：可调用外部搜索、数据库查询、文件操作等实时工具

            意图类型定义：
            - CHAT：普通对话、闲聊、问候、简单计算等，无需任何外部资源
            - KNOWLEDGE：需要查询内部知识库才能回答（如"文档中说的XX是什么"、"项目的XX规范"）
            - TOOL：需要调用外部工具获取实时数据（如搜索网络、查询数据库、执行操作）
            - MIXED：同时需要知识库和外部工具

            请严格返回如下JSON格式，不要有任何多余内容：
            {
              "intentType": "CHAT 或 KNOWLEDGE 或 TOOL 或 MIXED",
              "needsRag": true或false,
              "needsMcp": true或false,
              "reasoning": "一句话说明判断理由"
            }

            用户问题：%s
            """;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter,
                             DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        long startNs = System.nanoTime();
        log.info("\n🔍 Step0: 意图识别开始，用户输入: {}", requestParameter.getMessage());

        AiAgentClientFlowConfigVO configVO = dynamicContext.getAiAgentClientFlowConfigVOMap()
                .get(AiClientTypeEnumVO.INTENT_RECOGNIZER_CLIENT.getCode());

        String intentPrompt;
        ChatClient chatClient;

        if (configVO != null) {
            // 使用数据库配置的 prompt 模板（优先）
            intentPrompt = String.format(configVO.getStepPrompt(), requestParameter.getMessage());
            chatClient = getChatClientByClientId(configVO.getClientId());
        } else {
            // 回退：使用代码内置模板 + TASK_ANALYZER_CLIENT 的 ChatClient
            log.warn("未找到 INTENT_RECOGNIZER_CLIENT 配置，使用内置 prompt 和分析客户端");
            intentPrompt = String.format(INTENT_PROMPT_TEMPLATE, requestParameter.getMessage());
            AiAgentClientFlowConfigVO analyzerConfig = dynamicContext.getAiAgentClientFlowConfigVOMap()
                    .get(AiClientTypeEnumVO.TASK_ANALYZER_CLIENT.getCode());
            chatClient = getChatClientByClientId(analyzerConfig.getClientId());
        }

        IntentResult intentResult;
        try {
            String rawResult = chatClient
                    .prompt(intentPrompt)
                    .advisors(a -> a
                            .param(CHAT_MEMORY_CONVERSATION_ID_KEY, requestParameter.getSessionId())
                            .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 100))
                    .call().content();

            intentResult = parseIntentResult(rawResult, requestParameter.getMessage());
            dynamicContext.setIntentResult(intentResult);

            log.info("\n🔍 意图识别结果: type={}, needsRag={}, needsMcp={}, reasoning={}",
                    intentResult.getIntentType(), intentResult.isNeedsRag(),
                    intentResult.isNeedsMcp(), intentResult.getReasoning());

            // 通过 SSE 推送意图识别结果给前端
            AutoAgentExecuteResultEntity sseResult = AutoAgentExecuteResultEntity.createIntentResult(
                    intentResult.getIntentType(),
                    intentResult.isNeedsRag(),
                    intentResult.isNeedsMcp(),
                    intentResult.getReasoning(),
                    requestParameter.getSessionId());
            sendSseResult(dynamicContext, sseResult);
        } finally {
            metricsRegistry.recordStepDuration(requestParameter.getSessionId(), "Step0_IntentRecognition",
                    System.nanoTime() - startNs);
        }

        boolean fallback = intentResult.getReasoning() != null
                && intentResult.getReasoning().startsWith("降级策略");
        metricsRegistry.recordIntent(requestParameter.getSessionId(), intentResult.getIntentType(), fallback);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity requestParameter,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("step1AnalyzerNode");
    }

    /**
     * 解析 LLM 返回的 JSON 意图结果，解析失败时降级为 MIXED（最保守策略）
     */
    private IntentResult parseIntentResult(String raw, String userMessage) {
        if (raw == null || raw.isBlank()) {
            return fallbackIntent("LLM返回为空");
        }
        try {
            // 提取 JSON 块（LLM 有时会在前后加说明文字）
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start == -1 || end == -1 || end <= start) {
                return fallbackIntent("未找到JSON块");
            }
            String json = raw.substring(start, end + 1);
            IntentResult result = JSON.parseObject(json, IntentResult.class);
            if (result.getIntentType() == null) {
                return fallbackIntent("intentType字段缺失");
            }
            return result;
        } catch (Exception e) {
            log.warn("意图识别结果解析失败，降级为MIXED。raw={}, error={}", raw, e.getMessage());
            return fallbackIntent("JSON解析异常: " + e.getMessage());
        }
    }

    private IntentResult fallbackIntent(String reason) {
        return IntentResult.builder()
                .intentType("MIXED")
                .needsRag(true)
                .needsMcp(true)
                .reasoning("降级策略（" + reason + "），默认开启所有资源")
                .build();
    }
}
