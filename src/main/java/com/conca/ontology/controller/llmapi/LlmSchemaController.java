package com.conca.ontology.controller.llmapi;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.dto.ClassDeleteResult;
import com.conca.ontology.dto.ClassDetailView;
import com.conca.ontology.dto.ClassSummary;
import com.conca.ontology.dto.RelationView;
import com.conca.ontology.dto.SchemaView;
import com.conca.ontology.model.ClassDef;
import com.conca.ontology.service.SchemaService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * LLM 专属入口：概念层（schema 元数据）读写，供构建期概念抽取与落库。
 * 与 admin 入口（/api/schema）同源同逻辑，双入口隔离调用方。
 */
@Tag(name = "LLM-Schema", description = "概念层（schema 元数据）读写：类定义、属性、关系声明的全量视图与 CRUD。"
        + "唯一事实源 graph-schema.json，每次变更 version+1 并热更新写接口白名单。"
        + "与 admin 入口 /api/schema 同源同逻辑。")
@RestController
@RequestMapping("/api/llm/schema")
public class LlmSchemaController {

    private final SchemaService schemaService;

    public LlmSchemaController(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @Operation(summary = "全量 schema",
            description = "版本 + 全部类定义（属性/出边声明/实时 count）+ 全部关系扁平视图。"
                    + "构建期可直接整体注入 LLM 上下文。")
    @GetMapping
    public ApiResponse<SchemaView> schema() {
        return ApiResponse.ok(schemaService.schemaView());
    }

    @Operation(summary = "类列表（摘要）",
            description = "全部类的摘要行：名称/中文名/层/keyProp/说明/实时节点数/属性数/关系数。不含明细。")
    @GetMapping("/classes")
    public ApiResponse<List<ClassSummary>> classes() {
        return ApiResponse.ok(schemaService.classSummaries());
    }

    @Operation(summary = "单类详情",
            description = "类定义全字段 + 出边关系声明（本类声明）+ 入边关系（由其他类声明推导）+ 实时 count。")
    @GetMapping("/classes/{name}")
    public ApiResponse<ClassDetailView> classDetail(
            @Parameter(description = "类名（如 Era）", example = "Era") @PathVariable String name) {
        return ApiResponse.ok(schemaService.classView(name));
    }

    @Operation(summary = "新增类",
            description = "写入新类定义（含关系声明），成功后自动在 Neo4j 建唯一约束并热更新白名单。"
                    + "校验规则：name/keyProp 须为合法标识符；layer 只能 concept|instance；"
                    + "properties 非空且必须包含 keyProp；关系名全 schema 唯一；"
                    + "toClass 必须已存在（自引用除外）。已存在同名类返回 409。")
    @PostMapping("/classes")
    public ApiResponse<ClassDetailView> createClass(@RequestBody ClassDef def) {
        return ApiResponse.ok(schemaService.createClass(def));
    }

    @Operation(summary = "更新类（整体替换）",
            description = "整体替换该类的属性与关系声明。name 与 keyProp 不可变（违反返回 400）。"
                    + "被移除或改向的关系若库中仍有实例返回 409，需先删实例。")
    @PutMapping("/classes/{name}")
    public ApiResponse<ClassDetailView> updateClass(
            @Parameter(description = "类名（须与 body 的 name 一致）", example = "Era") @PathVariable String name,
            @RequestBody ClassDef def) {
        return ApiResponse.ok(schemaService.updateClass(name, def));
    }

    @Operation(summary = "删除类",
            description = "删除类定义并撤销唯一约束。保护：类被其他类关系引用（409）、"
                    + "库中仍有该类节点（409）时拒绝删除。")
    @DeleteMapping("/classes/{name}")
    public ApiResponse<ClassDeleteResult> deleteClass(
            @Parameter(description = "类名", example = "Era") @PathVariable String name) {
        return ApiResponse.ok(schemaService.deleteClass(name));
    }

    @Operation(summary = "关系扁平视图（全量只读）",
            description = "全部关系类型一条不漏：两端类 + 属性定义 + 语义说明/示例 + 实时数量。LLM 一次拿全。")
    @GetMapping("/relations")
    public ApiResponse<List<RelationView>> relations() {
        return ApiResponse.ok(schemaService.relationViews());
    }
}
