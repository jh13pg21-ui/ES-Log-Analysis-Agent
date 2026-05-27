package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.domain.agent.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.agent.service.IRagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 文档管理接口
 * <p>
 * POST   /api/v1/rag/upload              上传文档并向量化
 * GET    /api/v1/rag/documents?agentId=  查询 Agent 下所有文档
 * DELETE /api/v1/rag/documents/{docId}   删除文档（含 PgVector chunk）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/rag")
@CrossOrigin(origins = "*", allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.POST,
                RequestMethod.DELETE, RequestMethod.OPTIONS})
public class RagController {

    @Resource
    private IRagService ragService;

    /**
     * 上传文档
     *
     * @param agentId       归属 Agent ID（必填）
     * @param chunkStrategy 分块策略：fixed（默认）/ semantic
     * @param file          上传的文件（txt / md，PDF 需 tika 依赖）
     */
    @PostMapping("/upload")
    public Map<String, Object> upload(
            @RequestParam("agentId") String agentId,
            @RequestParam(value = "chunkStrategy", defaultValue = "fixed") String chunkStrategy,
            @RequestParam("file") MultipartFile file) {

        Map<String, Object> result = new HashMap<>();
        try {
            if (file.isEmpty()) {
                result.put("success", false);
                result.put("message", "文件不能为空");
                return result;
            }

            String fileName = file.getOriginalFilename();
            String fileType = getExtension(fileName);
            byte[] content = file.getBytes();

            log.info("RAG 文档上传 agentId:{} fileName:{} chunkStrategy:{} size:{}",
                    agentId, fileName, chunkStrategy, content.length);

            String documentId = ragService.uploadDocument(agentId, fileName, fileType, content, chunkStrategy);

            result.put("success", true);
            result.put("documentId", documentId);
            result.put("message", "上传成功，文档正在向量化处理中...");
        } catch (Exception e) {
            log.error("RAG 文档上传失败", e);
            result.put("success", false);
            result.put("message", "上传失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 查询 Agent 下所有文档列表
     */
    @GetMapping("/documents")
    public Map<String, Object> listDocuments(@RequestParam("agentId") String agentId) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<RagDocumentEntity> documents = ragService.listDocuments(agentId);
            result.put("success", true);
            result.put("documents", documents);
        } catch (Exception e) {
            log.error("RAG 文档查询失败 agentId:{}", agentId, e);
            result.put("success", false);
            result.put("message", "查询失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 删除文档（同时清除 PgVector 中所有对应 chunk）
     */
    @DeleteMapping("/documents/{documentId}")
    public Map<String, Object> deleteDocument(@PathVariable("documentId") String documentId) {
        Map<String, Object> result = new HashMap<>();
        try {
            ragService.deleteDocument(documentId);
            result.put("success", true);
            result.put("message", "文档删除成功");
        } catch (Exception e) {
            log.error("RAG 文档删除失败 documentId:{}", documentId, e);
            result.put("success", false);
            result.put("message", "删除失败：" + e.getMessage());
        }
        return result;
    }

    private String getExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "unknown";
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }

}
