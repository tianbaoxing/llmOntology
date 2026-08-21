package com.conca.ontology.controller.api;

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
import com.conca.ontology.dto.SchemaView;
import com.conca.ontology.model.ClassDef;
import com.conca.ontology.service.SchemaService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 概念层（schema 元数据）CRUD 入口：唯一事实源 graph-schema.json */
@Tag(name = "Admin-Schema", description = "概念层管理页接口：schema 元数据 CRUD（schema.html 数据源）。"
        + "与 LLM 平面 /api/llm/schema 同源同逻辑。")
@RestController
@RequestMapping("/api/schema")
public class SchemaController {

    private final SchemaService schemaService;

    public SchemaController(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @Operation(summary = "全量 schema", description = "版本 + 类定义列表 + 关系扁平视图（含实时 count）。")
    @GetMapping
    public ApiResponse<SchemaView> schema() {
        return ApiResponse.ok(schemaService.schemaView());
    }

    @Operation(summary = "类列表（摘要）", description = "全部类摘要：名称/中文名/层/keyProp/节点数/属性数/关系数。")
    @GetMapping("/classes")
    public ApiResponse<List<ClassSummary>> classes() {
        return ApiResponse.ok(schemaService.classSummaries());
    }

    @Operation(summary = "单类详情", description = "定义 + 出边声明 + 入边（推导）+ 实时 count。")
    @GetMapping("/classes/{name}")
    public ApiResponse<ClassDetailView> classDetail(
            @Parameter(description = "类名", example = "Era") @PathVariable String name) {
        return ApiResponse.ok(schemaService.classView(name));
    }

    @Operation(summary = "新增类", description = "写入类定义并自动建唯一约束；校验规则同 LLM 平面。")
    @PostMapping("/classes")
    public ApiResponse<ClassDetailView> createClass(@RequestBody ClassDef def) {
        return ApiResponse.ok(schemaService.createClass(def));
    }

    @Operation(summary = "更新类（整体替换）", description = "name/keyProp 不可变；被移除关系仍有实例时 409。")
    @PutMapping("/classes/{name}")
    public ApiResponse<ClassDetailView> updateClass(
            @Parameter(description = "类名（须与 body 一致）", example = "Era") @PathVariable String name,
            @RequestBody ClassDef def) {
        return ApiResponse.ok(schemaService.updateClass(name, def));
    }

    @Operation(summary = "删除类", description = "被引用或仍有实例 → 409。")
    @DeleteMapping("/classes/{name}")
    public ApiResponse<ClassDeleteResult> deleteClass(
            @Parameter(description = "类名", example = "Era") @PathVariable String name) {
        return ApiResponse.ok(schemaService.deleteClass(name));
    }
}
