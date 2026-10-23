package com.conca.ontology.llmapi.api;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.shared.web.ApiResponse;
import com.conca.ontology.llmapi.dto.OntologyQueryDTO;
import com.conca.ontology.llmapi.service.OntologyQueryExecutionService;

/**
 * @author tianbaoxing
 * @date 2026-08-20
 */
@RestController
@RequestMapping("/api/llm/ontology-query")
public class OntologyQueryController {

    private final OntologyQueryExecutionService queryExecutionService;

    public OntologyQueryController(OntologyQueryExecutionService queryExecutionService) {
        this.queryExecutionService = queryExecutionService;
    }

    @PostMapping("/query")
    public ApiResponse<Map<String, Object>> query(@Valid @RequestBody OntologyQueryDTO req) {
        return ApiResponse.ok(queryExecutionService.execute(req.question(), req.cypher(), req.params()));
    }
}
