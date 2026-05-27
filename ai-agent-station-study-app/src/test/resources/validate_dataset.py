#!/usr/bin/env python3
"""校验并清洗 RAG 评估数据集：剔除 expectedKeywords 在目标文档中不存在的用例"""
import json, os, re

RAG_DIR = r"C:\Users\WangCR\Desktop\PostGraduate\工作\项目\ai-agent-station-study\docs\dev-ops\RAG"
DATASET_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "rag-eval-dataset.json")

# 文档 ID → 文件名映射
DOC_FILES = {
    "doc_01": "01-项目日志字段与实体速查.md",
    "doc_02": "02-项目配置与安全阈值.md",
    "doc_03": "03-ES查询模板.md",
}

# 加载 RAG 文档内容
doc_contents = {}
for doc_id, filename in DOC_FILES.items():
    path = os.path.join(RAG_DIR, filename)
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            doc_contents[doc_id] = f.read().lower()
        print(f"Loaded {doc_id}: {filename} ({len(doc_contents[doc_id])} chars)")
    else:
        print(f"WARNING: {path} not found!")
        doc_contents[doc_id] = ""

# 加载数据集
with open(DATASET_FILE, encoding="utf-8") as f:
    cases = json.load(f)

print(f"\nOriginal cases: {len(cases)}")

# 校验每条用例
valid = []
invalid = []
for i, c in enumerate(cases):
    query = c["query"]
    keywords = c["keywords"] if "keywords" in c else c["expectedKeywords"]
    doc_ids = c["docIds"] if "docIds" in c else c["expectedDocIds"]

    # 拼出目标文档的完整文本
    target_text = ""
    for did in doc_ids:
        if did in doc_contents:
            target_text += " " + doc_contents[did]

    if not target_text:
        invalid.append((i, query, "no target docs"))
        continue

    # 检查至少有几个关键词命中目标文档
    hit_count = 0
    missed = []
    for kw in keywords:
        if kw.lower() in target_text:
            hit_count += 1
        else:
            missed.append(kw)

    min_docs = c.get("minRelevantDocs", 1)

    # 至少 50% 的 expectedKeywords 命中才算有效用例
    hit_ratio = hit_count / len(keywords) if keywords else 0
    if hit_ratio >= 0.5:
        # 更新 minRelevantDocs = 实际命中的文档数（至少 1）
        c["minRelevantDocs"] = max(1, sum(1 for did in doc_ids if did in doc_contents))
        valid.append(c)
    else:
        reason = f"hit_ratio={hit_ratio:.1f} ({hit_count}/{len(keywords)}), missed: {missed[:5]}"
        invalid.append((i, query, reason))

print(f"Valid cases:   {len(valid)}")
print(f"Invalid cases: {len(invalid)}")
if invalid:
    print("\n--- 被剔除的用例（前 20 条）---")
    for idx, q, reason in invalid[:20]:
        print(f"  [{idx}] {q[:60]}...")
        print(f"       reason: {reason}")

# 保存校验后的数据集
valid = valid[:200]  # 最多保留 200
with open(DATASET_FILE, "w", encoding="utf-8") as f:
    json.dump(valid, f, ensure_ascii=False, indent=2)

print(f"\nFinal cases written: {len(valid)}")

# 统计
d01 = sum(1 for c in valid if "doc_01" in c.get("docIds", c.get("expectedDocIds", [])))
d02 = sum(1 for c in valid if "doc_02" in c.get("docIds", c.get("expectedDocIds", [])))
d03 = sum(1 for c in valid if "doc_03" in c.get("docIds", c.get("expectedDocIds", [])))
mixed = sum(1 for c in valid if len(c.get("docIds", c.get("expectedDocIds", []))) > 1)
print(f"  doc_01: {d01}, doc_02: {d02}, doc_03: {d03}, mixed: {mixed}")
