package com.conca.ontology.controller.ontologyquery;

import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.dto.ontologyquery.OntologyQueryRequest;
import com.conca.ontology.service.ontologyquery.OntologyQueryExecutionService;

/**
 * 本体查询专用入口：
 * - /query 接受只读 Cypher，执行图谱查询
 */
@RestController
@RequestMapping("/api/llm/ontology-query")
public class OntologyQueryController {

    private final OntologyQueryExecutionService queryExecutionService;

    public OntologyQueryController(OntologyQueryExecutionService queryExecutionService) {
        this.queryExecutionService = queryExecutionService;
    }

    @PostMapping("/query")
    public ApiResponse<Map<String, Object>> query(@Valid @RequestBody OntologyQueryRequest req) {
        return ApiResponse.ok(queryExecutionService.execute(req.question(), req.cypher(), req.params()));
    }
}
