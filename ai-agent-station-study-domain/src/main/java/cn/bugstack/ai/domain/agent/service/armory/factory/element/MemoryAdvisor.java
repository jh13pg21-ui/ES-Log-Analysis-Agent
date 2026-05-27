package cn.bugstack.ai.domain.agent.service.armory.factory.element;

import cn.bugstack.ai.domain.agent.service.metrics.AgentMetricsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 长期记忆 Advisor
 * <p>
 * 运行时机（order = -1，早于 RagAnswerAdvisor）：
 *   before(): 从 pgVector 检索与当前问题语义相关的历史摘要，注入到用户消息中
 *   after() : 追踪本轮对话内容；累计满 consolidateEveryN 轮时，异步触发
 *             LLM 摘要压缩，并将摘要向量化存回 pgVector
 * <p>
 * 记忆分区：同一 agentId 下所有 session 共享，向量表 metadata 使用
 *   agent_id = "memory_{agentId}" 与普通 RAG 文档隔离
 */
public class MemoryAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(MemoryAdvisor.class);

    /** advisor 参数 key：Spring AI 标准的 session/conversationId */
    private static final String SESSION_ID_KEY = "chat_memory_conversation_id";

    /** 向量库 agent_id 前缀，与 RAG 知识库隔离 */
    private static final String MEMORY_ID_PREFIX = "memory_";

    /** 注入 prompt 中的记忆片段分隔符 */
    private static final String MEMORY_SECTION_START =
            "\n\n[相关历史对话摘要 - 仅供参考]\n---------------------\n";
    private static final String MEMORY_SECTION_END = "\n---------------------\n";

    private final VectorStore vectorStore;
    private final ChatModel chatModel;
    /** 向量库分区键，格式为 memory_{agentId} */
    private final String memoryAgentId;
    /** 每 N 轮触发一次摘要 */
    private final int consolidateEveryN;
    /** 检索历史摘要时的最大返回条数 */
    private final int topK;
    /** 可观测指标，可为 null */
    private final AgentMetricsRegistry metricsRegistry;

    /**
     * sessionId → 本批次积累的对话行 ["用户: ...", "助手: ...", ...]
     * 每次触发压缩后清空已压缩部分
     */
    private final ConcurrentHashMap<String, List<String>> sessionBuffer = new ConcurrentHashMap<>();
    /** sessionId → 已完成的对话轮数（user+assistant 为一轮） */
    private final ConcurrentHashMap<String, Integer> roundCounters = new ConcurrentHashMap<>();

    public MemoryAdvisor(VectorStore vectorStore, ChatModel chatModel,
                         String agentId, int consolidateEveryN, int topK) {
        this(vectorStore, chatModel, agentId, consolidateEveryN, topK, null);
    }

    public MemoryAdvisor(VectorStore vectorStore, ChatModel chatModel,
                         String agentId, int consolidateEveryN, int topK,
                         AgentMetricsRegistry metricsRegistry) {
        this.vectorStore = vectorStore;
        this.chatModel = chatModel;
        this.memoryAgentId = MEMORY_ID_PREFIX + agentId;
        this.consolidateEveryN = consolidateEveryN;
        this.topK = topK;
        this.metricsRegistry = metricsRegistry;
    }

    // ── Advisor 入口 ─────────────────────────────────────────────────────

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        String userText = chatClientRequest.prompt().getUserMessage().getText();
        String sessionId = getSessionId(chatClientRequest.context());

        // 追踪用户消息（after() 时补充助手回复）
        if (StringUtils.hasText(sessionId)) {
            sessionBuffer.computeIfAbsent(sessionId, k -> new ArrayList<>())
                    .add("用户: " + userText);
        }

        // 检索与当前问题语义相关的历史摘要
        List<Document> memories = retrieveMemories(userText);
        if (metricsRegistry != null) {
            metricsRegistry.recordLongMemoryQuery(sessionId, memories.size());
        }
        if (memories.isEmpty()) {
            return chatClientRequest;
        }

        String memoryContext = memories.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n"));

        String advisedUserText = userText
                + MEMORY_SECTION_START
                + memoryContext
                + MEMORY_SECTION_END;

        return ChatClientRequest.builder()
                .prompt(Prompt.builder()
                        .messages(new UserMessage(advisedUserText))
                        .build())
                .context(chatClientRequest.context())
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        String sessionId = getSessionId(chatClientResponse.context());
        if (!StringUtils.hasText(sessionId)) return chatClientResponse;

        // 追踪助手回复
        String assistantText = "";
        try {
            assistantText = chatClientResponse.chatResponse().getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("MemoryAdvisor: 无法获取助手回复文本，跳过本轮追踪");
            return chatClientResponse;
        }

        if (!StringUtils.hasText(assistantText)) return chatClientResponse;

        List<String> buffer = sessionBuffer.computeIfAbsent(sessionId, k -> new ArrayList<>());
        buffer.add("助手: " + assistantText);

        // 计算完整轮数（user + assistant = 1 轮）
        int totalRounds = roundCounters.merge(sessionId, 1, Integer::sum);

        // 达到 N 轮时异步触发摘要
        if (totalRounds % consolidateEveryN == 0) {
            // 取本批次所有消息做摘要，然后只保留最新一轮（避免重复压缩）
            List<String> snapshot = new ArrayList<>(buffer);
            buffer.clear();

            String sid = sessionId;
            CompletableFuture.runAsync(() -> consolidateMemory(snapshot, sid));
        }

        return chatClientResponse;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        ChatClientResponse response = callAdvisorChain.nextCall(this.before(chatClientRequest, callAdvisorChain));
        return this.after(response, callAdvisorChain);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
                                                  StreamAdvisorChain streamAdvisorChain) {
        return BaseAdvisor.super.adviseStream(chatClientRequest, streamAdvisorChain);
    }

    /** 早于 RagAnswerAdvisor (order=0) 运行，确保记忆先注入，RAG 在其之上叠加 */
    @Override
    public int getOrder() { return -1; }

    @Override
    public String getName() { return this.getClass().getSimpleName(); }

    // ── 核心方法 ─────────────────────────────────────────────────────────

    /**
     * 向量检索历史摘要：filter 为 agent_id == 'memory_{agentId}'
     */
    private List<Document> retrieveMemories(String query) {
        try {
            return vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .filterExpression("agent_id == '" + memoryAgentId + "'")
                            .build()
            );
        } catch (Exception e) {
            log.warn("MemoryAdvisor: 历史摘要检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 摘要压缩 + 向量存储（异步执行）
     * <p>
     * LLM 将本批次对话压缩为要点摘要，写入 pgVector，
     * metadata.agent_id = "memory_{agentId}" 实现与 RAG 隔离。
     */
    private void consolidateMemory(List<String> messages, String sessionId) {
        try {
            String dialogue = String.join("\n", messages);
            String summaryPrompt = "请将以下对话压缩为简洁的要点摘要，保留关键事实、用户提出的问题和重要结论。\n"
                    + "要求：结构清晰、信息密度高、不超过200字。\n\n"
                    + "对话内容：\n" + dialogue
                    + "\n\n只返回摘要正文，不要任何前缀或解释。";

            String summary = chatModel.call(new Prompt(summaryPrompt))
                    .getResult().getOutput().getText();

            if (!StringUtils.hasText(summary)) {
                log.warn("MemoryAdvisor: LLM 返回空摘要，跳过存储");
                return;
            }

            Document memoryDoc = new Document(summary.trim(), Map.of(
                    "agent_id", memoryAgentId,
                    "session_id", sessionId,
                    "timestamp", String.valueOf(System.currentTimeMillis()),
                    "type", "memory"
            ));
            vectorStore.add(List.of(memoryDoc));

            if (metricsRegistry != null) {
                metricsRegistry.recordLongMemoryConsolidate(sessionId);
            }
            log.info("MemoryAdvisor: 长期记忆已存储 agentId={} sessionId={} 摘要={}字",
                    memoryAgentId, sessionId, summary.trim().length());
        } catch (Exception e) {
            log.error("MemoryAdvisor: 长期记忆存储失败: {}", e.getMessage(), e);
        }
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────

    private String getSessionId(Map<String, Object> context) {
        Object val = context.get(SESSION_ID_KEY);
        return val != null ? val.toString() : null;
    }
}
