package cn.bugstack.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 顾问配置，值对象
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/6/27 18:42
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiClientAdvisorVO {

    /**
     * 顾问ID
     */
    private String advisorId;

    /**
     * 顾问名称
     */
    private String advisorName;

    /**
     * 顾问类型(PromptChatMemory/RagAnswer/SimpleLoggerAdvisor等)
     */
    private String advisorType;

    /**
     * 顺序号
     */
    private Integer orderNum;

    /**
     * 扩展；记忆
     */
    private ChatMemory chatMemory;

    /**
     * 扩展；rag 问答
     */
    private RagAnswer ragAnswer;

    /**
     * 扩展；长期记忆
     */
    private MemoryAnswer memoryAnswer;

    /**
     * 扩展；摘要缓冲短期记忆
     */
    private SummaryBufferMemory summaryBufferMemory;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ChatMemory {
        private int maxMessages;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RagAnswer {
        private int topK = 4;
        private String filterExpression;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class MemoryAnswer {
        /** 所属 agent ID，用于向量库分区存储（同 agentId 的所有 session 共享） */
        private String agentId;
        /** 每 N 轮对话触发一次摘要压缩 */
        private int consolidateEveryN = 5;
        /** 检索历史摘要时返回的最大条数 */
        private int topK = 3;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SummaryBufferMemory {
        /** 明细区最大消息条数；超出后将最老的 compressSize 条压缩进摘要 */
        @lombok.Builder.Default
        private int maxRawMessages = 20;
        /** 每次触发压缩时处理的消息条数 */
        @lombok.Builder.Default
        private int compressSize = 10;
    }

}
