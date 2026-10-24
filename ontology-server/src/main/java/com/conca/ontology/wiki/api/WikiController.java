package com.conca.ontology.wiki.api;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.shared.web.ApiResponse;
import com.conca.ontology.wiki.dto.WikiMetaDTO;
import com.conca.ontology.wiki.dto.WikiBySourceResultDTO;
import com.conca.ontology.wiki.dto.WikiDocResultDTO;
import com.conca.ontology.wiki.dto.WikiMetaUpdateResultDTO;
import com.conca.ontology.wiki.dto.WikiSearchResultDTO;
import com.conca.ontology.wiki.dto.WikiListDTO;
import com.conca.ontology.wiki.service.WikiService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 管理端入口：Wiki 知识库（wiki.html 数据源，仅 ADMIN）。
 * 只读呈现 files-md/ 转换产物：列表 = SUCCESS 条目 + 元信息自动抽取/手工维护合并。
 */
@Tag(name = "Admin-Wiki", description = "Wiki 知识库（wiki.html 数据源）：文档列表、单篇读取（MD 原文 + 元信息）、"
        + "编辑分类/标签（wiki_metadata 表）。")

/**
 * @author tianbaoxing
 * @date 2026-09-23
 */
@RestController
@RequestMapping("/api/wiki")
public class WikiController {

    private final WikiService wikiService;

    public WikiController(WikiService wikiService) {
        this.wikiService = wikiService;
    }

    @Operation(summary = "文档列表", description = "仅含转换 SUCCESS 的 MD（与 LLM 可见性一致）；"
            + "元信息 = 正文自动抽取 + wiki_metadata 表手工维护合并；顺带清理孤儿元数据。")
    @GetMapping
    public ApiResponse<WikiListDTO> list() {
        return ApiResponse.ok(wikiService.list());
    }

    @Operation(summary = "单篇文档", description = "name 为 MD 文件名（查询参数，可含中文/空格）；"
            + "返回 MD 原文，前端 marked + DOMPurify 渲染；"
            + "entities 为正文实际命中的图谱实体（概念层 + 实例层，结果缓存随重转失效），前端包裹链接跳 /instances。")
    @GetMapping("/doc")
    public ApiResponse<WikiDocResultDTO> doc(
            @Parameter(description = "MD 文件名", example = "报告.docx.md") @RequestParam String name) {
        return ApiResponse.ok(wikiService.doc(name));
    }

    @Operation(summary = "全文搜索", description = "逐篇扫描 SUCCESS 状态的 MD 正文，toLower 包含匹配；"
            + "加权分 = 标题 ×40 + 元信息 ×20 + 正文单次 ×1（降序，标题命中的文档排在纯正文命中之前）；"
            + "每篇最多 3 个命中片段，结果上限 50 篇；空白关键词返回空列表。")
    @GetMapping("/search")
    public ApiResponse<WikiSearchResultDTO> search(
            @Parameter(description = "关键词", example = "陨铁") @RequestParam String q) {
        return ApiResponse.ok(wikiService.search(q));
    }

    @Operation(summary = "按来源文件查相关文档", description = "实例详情反向溯源：按节点的 _sourceFile"
            + "（原始上传文件名）匹配文档 sourceName（两侧均源自 FileEntry.name，字符串相等）；"
            + "供 instances 页实例展开属性区的「相关文档」块懒加载。")
    @GetMapping("/by-source")
    public ApiResponse<WikiBySourceResultDTO> bySource(
            @Parameter(description = "原始上传文件名", example = "三星堆陨铁器分析报告.docx")
            @RequestParam String file) {
        return ApiResponse.ok(wikiService.bySource(file));
    }

    @Operation(summary = "编辑元信息", description = "{*name} 为 MD 文件名（支持子目录路径，入参带前导斜杠需截去）；"
            + "category/tags 置空即清除。")
    @PutMapping("/meta/{*name}")
    public ApiResponse<WikiMetaUpdateResultDTO> updateMeta(
            @Parameter(description = "MD 文件名（可含子目录路径）", example = "报告.docx.md") @PathVariable String name,
            @Valid @RequestBody WikiMetaDTO req) {
        return ApiResponse.ok(wikiService.updateMeta(stripSlash(name), req.category(), req.tags()));
    }

    private static String stripSlash(String name) {
        return name.startsWith("/") ? name.substring(1) : name;
    }
}
