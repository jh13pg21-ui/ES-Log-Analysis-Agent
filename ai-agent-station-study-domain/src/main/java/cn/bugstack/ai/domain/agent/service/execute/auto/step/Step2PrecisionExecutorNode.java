package cn.bugstack.ai.domain.agent.service.execute.auto.step;

import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.bugstack.ai.domain.agent.service.armory.AiClientNode;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory.IntentResult;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.stereotype.Service;

/**
 * 精准执行节点
 * <p>
 * 根据 Step0 的意图识别结果，决定是否动态注入 RAG Advisor。
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/7/27 16:42
 */
@Slf4j
@Service
public class Step2PrecisionExecutorNode extends AbstractExecuteSupport {

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        long startNs = System.nanoTime();
        log.info("\n⚡ 阶段2: 精准任务执行");

        try {
            // 从动态上下文中获取分析结果
            String analysisResult = dynamicContext.getValue("analysisResult");
            if (analysisResult == null || analysisResult.trim().isEmpty()) {
                log.warn("⚠️ 分析结果为空，使用默认执行策略");
                analysisResult = "执行当前任务步骤";
            }

            AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO = dynamicContext.getAiAgentClientFlowConfigVOMap()
                    .get(AiClientTypeEnumVO.PRECISION_EXECUTOR_CLIENT.getCode());

            String executionPrompt = String.format(aiAgentClientFlowConfigVO.getStepPrompt(),
                    requestParameter.getMessage(), analysisResult);

            // 获取对话客户端
            ChatClient chatClient = getChatClientByClientId(aiAgentClientFlowConfigVO.getClientId());

            // 根据意图识别结果决定是否注入 RAG Advisor
            IntentResult intentResult = dynamicContext.getIntentResult();
            boolean needsRag = intentResult != null && intentResult.isNeedsRag();

            String executionResult;
            if (needsRag) {
                log.info("⚡ 意图需要知识库，动态注入 RAG Advisor");
                Advisor ragAdvisor = tryGetRagAdvisor(aiAgentClientFlowConfigVO.getClientId());
                if (ragAdvisor != null) {
                    executionResult = chatClient
                            .prompt(executionPrompt)
                            .advisors(ragAdvisor)
                            .advisors(a -> a
                                    .param(CHAT_MEMORY_CONVERSATION_ID_KEY, requestParameter.getSessionId())
                                    .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 1024)
                                    .param("qa_filter_expression",
                                            "agent_id == '" + requestParameter.getAiAgentId() + "'"))
                            .call().content();
                } else {
                    log.warn("⚠️ 未找到 RAG Advisor bean，降级为普通执行");
                    executionResult = callWithMemoryOnly(chatClient, executionPrompt, requestParameter.getSessionId());
                }
            } else {
                log.info("⚡ 意图不需要知识库，跳过 RAG 查询");
                executionResult = callWithMemoryOnly(chatClient, executionPrompt, requestParameter.getSessionId());
            }

            assert executionResult != null;
            parseExecutionResult(dynamicContext, executionResult, requestParameter.getSessionId());

            // 将执行结果保存到动态上下文中，供下一步使用
            dynamicContext.setValue("executionResult", executionResult);

            // 更新执行历史
            String stepSummary = String.format("""
                    === 第 %d 步执行记录 ===
                    【分析阶段】%s
                    【执行阶段】%s
                    """, dynamicContext.getStep(), analysisResult, executionResult);

            dynamicContext.getHistoryEntries().add(stepSummary);
        } finally {
            metricsRegistry.recordStepDuration(requestParameter.getSessionId(), "Step2_PrecisionExecutor",
                    System.nanoTime() - startNs);
        }
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("step3QualitySupervisorNode");
    }

    private String callWithMemoryOnly(ChatClient chatClient, String prompt, String sessionId) {
        return chatClient
                .prompt(prompt)
                .advisors(a -> a
                        .param(CHAT_MEMORY_CONVERSATION_ID_KEY, sessionId)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 1024))
                .call().content();
    }

    /**
     * 尝试获取当前 client 对应的 RAG Advisor bean，不存在时返回 null
     */
    private Advisor tryGetRagAdvisor(String clientId) {
        try {
            return getBean(AiClientNode.RAG_ADVISOR_BEAN_PREFIX + clientId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析执行结果
     */
    private void parseExecutionResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, String executionResult, String sessionId) {
        int step = dynamicContext.getStep();
        log.info("\n⚡ === 第 {} 步执行结果 ===", step);

        String[] lines = executionResult.split("\n");
        String currentSection = "";
        StringBuilder sectionContent = new StringBuilder();

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.contains("执行目标:")) {
                sendExecutionSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "execution_target";
                sectionContent = new StringBuilder();
                log.info("\n🎯 执行目标:");
                continue;
            } else if (line.contains("执行过程:")) {
                sendExecutionSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "execution_process";
                sectionContent = new StringBuilder();
                log.info("\n🔧 执行过程:");
                continue;
            } else if (line.contains("执行结果:")) {
                sendExecutionSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "execution_result";
                sectionContent = new StringBuilder();
                log.info("\n📈 执行结果:");
                continue;
            } else if (line.contains("质量检查:")) {
                sendExecutionSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
                currentSection = "execution_quality";
                sectionContent = new StringBuilder();
                log.info("\n🔍 质量检查:");
                continue;
            }

            if (!currentSection.isEmpty()) {
                sectionContent.append(line).append("\n");
                switch (currentSection) {
                    case "execution_target":
                        log.info("   🎯 {}", line);
                        break;
                    case "execution_process":
                        log.info("   ⚙️ {}", line);
                        break;
                    case "execution_result":
                        log.info("   📊 {}", line);
                        break;
                    case "execution_quality":
                        log.info("   ✅ {}", line);
                        break;
                    default:
                        log.info("   📝 {}", line);
                        break;
                }
            }
        }

        sendExecutionSubResult(dynamicContext, currentSection, sectionContent.toString(), sessionId);
    }

    /**
     * 发送执行阶段细分结果到流式输出
     */
    private void sendExecutionSubResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                        String subType, String content, String sessionId) {
        if (!subType.isEmpty() && !content.isEmpty()) {
            AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createExecutionSubResult(
                    dynamicContext.getStep(), subType, content, sessionId);
            sendSseResult(dynamicContext, result);
        }
    }

}
