package com.conca.ontology.controller.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.model.ClassDef;
import com.conca.ontology.service.SchemaService;

/** 概念层（schema 元数据）CRUD 入口：唯一事实源 graph-schema.json */
@RestController
@RequestMapping("/api/schema")
public class SchemaController {

    private final SchemaService schemaService;

    public SchemaController(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    /** 全量 schema：类型 + 关系 + 属性 + 实时 count（可直接注入 LLM 上下文） */
    @GetMapping
    public ApiResponse<Map<String, Object>> schema() {
        return ApiResponse.ok(schemaService.schemaView());
    }

    /** 类型列表（摘要 + 实时 count） */
    @GetMapping("/classes")
    public ApiResponse<List<Map<String, Object>>> classes() {
        return ApiResponse.ok(schemaService.classSummaries());
    }

    /** 单类详情：属性 + 出边（声明）+ 入边（推导）+ count */
    @GetMapping("/classes/{name}")
    public ApiResponse<Map<String, Object>> classDetail(@PathVariable String name) {
        return ApiResponse.ok(schemaService.classView(name));
    }

    /** 新增类（含关系声明），成功后自动建唯一约束并热更新白名单 */
    @PostMapping("/classes")
    public ApiResponse<Map<String, Object>> createClass(@RequestBody ClassDef def) {
        return ApiResponse.ok(schemaService.createClass(def));
    }

    /** 更新类（name/keyProp 不可变；整体替换属性与关系声明） */
    @PutMapping("/classes/{name}")
    public ApiResponse<Map<String, Object>> updateClass(@PathVariable String name, @RequestBody ClassDef def) {
        return ApiResponse.ok(schemaService.updateClass(name, def));
    }

    /** 删除类（被引用或仍有实例 → 409） */
    @DeleteMapping("/classes/{name}")
    public ApiResponse<Map<String, Object>> deleteClass(@PathVariable String name) {
        return ApiResponse.ok(schemaService.deleteClass(name));
    }
}
