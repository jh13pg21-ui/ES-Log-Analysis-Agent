package cn.bugstack.ai.domain.agent.service.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.*;

/**
 * PDF 结构化文档读取器
 * <p>
 * 按 PDF 章节（outline/bookmark）切分文档，保留章节标题和页码元数据。
 * 无章节结构的 PDF 降级为按页切分。
 */
@Component
public class PdfStructureDocumentReader {

    /**
     * 读取 PDF 并按章节/页面切分，保留结构信息
     *
     * @param pdfBytes  PDF 原始字节
     * @param baseMetadata 基础元数据（agent_id, document_id 等）
     * @return 每个章节/页面为一个 Document
     */
    public List<Document> read(byte[] pdfBytes, Map<String, Object> baseMetadata) {
        List<Document> documents = new ArrayList<>();

        try (PDDocument pdDoc = Loader.loadPDF(pdfBytes)) {
            int totalPages = pdDoc.getNumberOfPages();

            PDDocumentOutline outline = pdDoc.getDocumentCatalog().getDocumentOutline();
            if (outline != null && !outline.children().isEmpty()) {
                // 有章节结构 → 按章节切
                documents.addAll(splitByOutline(pdDoc, outline, totalPages, baseMetadata));
            } else {
                // 无章节结构 → 降级按页切
                documents.addAll(splitByPage(pdDoc, totalPages, baseMetadata));
            }
        } catch (Exception e) {
            throw new RuntimeException("PDF 结构化读取失败: " + e.getMessage(), e);
        }
        return documents;
    }

    /**
     * 按 PDF 书签/大纲切分
     */
    private List<Document> splitByOutline(PDDocument pdDoc, PDDocumentOutline outline,
                                           int totalPages, Map<String, Object> baseMetadata) {
        List<Document> documents = new ArrayList<>();
        List<ChapterInfo> chapters = flattenOutline(outline, totalPages);
        PDFTextStripper stripper = createStripper();

        for (int i = 0; i < chapters.size(); i++) {
            ChapterInfo ch = chapters.get(i);
            try {
                stripper.setStartPage(ch.startPage);
                stripper.setEndPage(ch.endPage);
                String text = stripper.getText(pdDoc).trim();

                if (text.isEmpty()) continue;

                Map<String, Object> md = new HashMap<>(baseMetadata);
                md.put("chapter_title", ch.title);
                md.put("page_range", ch.startPage + "-" + ch.endPage);
                md.put("chapter_index", i);
                md.put("chapter_total", chapters.size());
                md.put("total_pages", totalPages);

                documents.add(new Document(text, md));
            } catch (Exception e) {
                // 单章失败不影响其他章节
            }
        }
        return documents;
    }

    /**
     * 无章节时按页切分，每 2 页合并为一个 Document（避免单页太碎）
     */
    private List<Document> splitByPage(PDDocument pdDoc, int totalPages,
                                        Map<String, Object> baseMetadata) {
        List<Document> documents = new ArrayList<>();
        int pagesPerGroup = 2;

        for (int start = 1; start <= totalPages; start += pagesPerGroup) {
            int end = Math.min(start + pagesPerGroup - 1, totalPages);
            try {
                PDFTextStripper stripper = createStripper();
                stripper.setStartPage(start);
                stripper.setEndPage(end);
                String text = stripper.getText(pdDoc).trim();

                if (text.isEmpty()) continue;

                Map<String, Object> md = new HashMap<>(baseMetadata);
                md.put("page_range", start + "-" + end);
                md.put("page_total", totalPages);
                md.put("split_method", "by_page");

                documents.add(new Document(text, md));
            } catch (Exception e) {
                // 单页失败不影响其他页
            }
        }
        return documents;
    }

    /**
     * 展开多级大纲为扁平列表，每个条目计算起止页码
     */
    private List<ChapterInfo> flattenOutline(PDDocumentOutline outline, int totalPages) {
        List<ChapterInfo> flat = new ArrayList<>();
        traverseOutline(outline.children(), flat);
        // 计算每章的结束页 = 下一章的起始页 - 1，最后一章到最后一页
        for (int i = 0; i < flat.size(); i++) {
            int end = (i < flat.size() - 1) ? flat.get(i + 1).startPage - 1 : totalPages;
            flat.get(i).endPage = Math.max(flat.get(i).startPage, end);
        }
        return flat;
    }

    private void traverseOutline(Iterable<PDOutlineItem> items, List<ChapterInfo> flat) {
        for (PDOutlineItem item : items) {
            try {
                // PDFBox 的 findDestinationPage 返回 0-based 页码
                int page = item.findDestinationPage(pdDoc) + 1;
                String title = item.getTitle();
                if (title == null || title.isBlank()) title = "未命名章节";
                flat.add(new ChapterInfo(title, page, 0));
                // 递归子章节
                if (item.children().iterator().hasNext()) {
                    traverseOutline(item.children(), flat);
                }
            } catch (Exception e) {
                // skip malformed outline item
            }
        }
    }

    private PDFTextStripper createStripper() {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setAddMoreFormatting(false);
        return stripper;
    }

    private static class ChapterInfo {
        final String title;
        final int startPage;
        int endPage;

        ChapterInfo(String title, int startPage, int endPage) {
            this.title = title;
            this.startPage = startPage;
            this.endPage = endPage;
        }
    }
}
