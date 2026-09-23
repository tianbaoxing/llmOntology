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
import com.conca.ontology.wiki.dto.request.WikiMetaRequest;
import com.conca.ontology.wiki.dto.result.WikiDocResult;
import com.conca.ontology.wiki.dto.result.WikiMetaUpdateResult;
import com.conca.ontology.wiki.dto.view.WikiListView;
import com.conca.ontology.wiki.service.WikiService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 管理端入口：Wiki 知识库（wiki.html 数据源，仅 ADMIN）。
 * 只读呈现 files-md/ 转换产物：列表 = SUCCESS 条目 + 元信息自动抽取/手工维护合并。
 */
@Tag(name = "Admin-Wiki", description = "Wiki 知识库（wiki.html 数据源）：文档列表、单篇读取（MD 原文 + 元信息）、"
        + "编辑分类/标签（data/wiki-metadata.json）。")
@RestController
@RequestMapping("/api/wiki")
public class WikiController {

    private final WikiService wikiService;

    public WikiController(WikiService wikiService) {
        this.wikiService = wikiService;
    }

    @Operation(summary = "文档列表", description = "仅含转换 SUCCESS 的 MD（与 LLM 可见性一致）；"
            + "元信息 = 正文自动抽取 + wiki-metadata.json 手工维护合并；顺带清理孤儿元数据。")
    @GetMapping
    public ApiResponse<WikiListView> list() {
        return ApiResponse.ok(wikiService.list());
    }

    @Operation(summary = "单篇文档", description = "name 为 MD 文件名（查询参数，可含中文/空格）；"
            + "返回 MD 原文，前端 marked + DOMPurify 渲染。")
    @GetMapping("/doc")
    public ApiResponse<WikiDocResult> doc(
            @Parameter(description = "MD 文件名", example = "报告.docx.md") @RequestParam String name) {
        return ApiResponse.ok(wikiService.doc(name));
    }

    @Operation(summary = "编辑元信息", description = "{*name} 为 MD 文件名（支持子目录路径，入参带前导斜杠需截去）；"
            + "category/tags 置空即清除。")
    @PutMapping("/meta/{*name}")
    public ApiResponse<WikiMetaUpdateResult> updateMeta(
            @Parameter(description = "MD 文件名（可含子目录路径）", example = "报告.docx.md") @PathVariable String name,
            @Valid @RequestBody WikiMetaRequest req) {
        return ApiResponse.ok(wikiService.updateMeta(stripSlash(name), req.category(), req.tags()));
    }

    private static String stripSlash(String name) {
        return name.startsWith("/") ? name.substring(1) : name;
    }
}
