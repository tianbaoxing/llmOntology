package com.conca.ontology.controller.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.dto.ApiResponse;
import com.conca.ontology.model.ForbiddenWordFile;
import com.conca.ontology.model.content.ContentSecurityAllowlistFile;
import com.conca.ontology.model.content.ContentSecurityCheckResult;
import com.conca.ontology.model.content.ContentSecurityRule;
import com.conca.ontology.model.content.ContentSecurityRuleFile;
import com.conca.ontology.model.content.PromptInjectionScanResult;
import com.conca.ontology.service.ContentSecurityService;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Admin-ContentSecurity", description = "内容安全一体化管理：禁用词 + 28 条 Prompt 注入规则 + allowlist + 扫描测试")
@RestController
@RequestMapping("/api/content-security")
public class ContentSecurityController {

    private final ContentSecurityService securityService;

    public ContentSecurityController(ContentSecurityService securityService) {
        this.securityService = securityService;
    }

    @Operation(summary = "全量规则（含 version）", description = "读取完整 content-security-rules.json")
    @GetMapping("/rules")
    public ApiResponse<ContentSecurityRuleFile> getAllRules() {
        return ApiResponse.ok(securityService.loadAllRules());
    }

    @Operation(summary = "新增自定义规则", description = "body = ContentSecurityRule JSON；ID 唯一")
    @PostMapping("/rules")
    public ApiResponse<ContentSecurityRuleFile> addRule(@RequestBody ContentSecurityRule rule) {
        return ApiResponse.ok(securityService.addRule(rule));
    }

    @Operation(summary = "删除自定义规则", description = "按 ruleId 删除；内置规则也允许删（删完 reload 生效）")
    @DeleteMapping("/rules/{ruleId}")
    public ApiResponse<ContentSecurityRuleFile> deleteRule(@PathVariable String ruleId) {
        return ApiResponse.ok(securityService.deleteRule(ruleId));
    }

    @Operation(summary = "allowlist 规则 ID 数组", description = "读取 content-security-allowlist.json")
    @GetMapping("/allowlist")
    public ApiResponse<ContentSecurityAllowlistFile> getAllowlist() {
        return ApiResponse.ok(securityService.loadAllowlist());
    }

    @Operation(summary = "添加 allowlist", description = "body: { \"ruleId\": \"xxx\" }")
    @PostMapping("/allowlist")
    public ApiResponse<ContentSecurityAllowlistFile> addAllowlist(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(securityService.addAllowlist(body.get("ruleId")));
    }

    @Operation(summary = "移出 allowlist")
    @DeleteMapping("/allowlist/{ruleId}")
    public ApiResponse<ContentSecurityAllowlistFile> removeAllowlist(@PathVariable String ruleId) {
        return ApiResponse.ok(securityService.removeAllowlist(ruleId));
    }

    @Operation(summary = "全量禁用词（代理转发原结构）")
    @GetMapping("/forbidden-words")
    public ApiResponse<ForbiddenWordFile> getAllForbiddenWords() {
        return ApiResponse.ok(securityService.loadAllForbiddenWords());
    }

    @Operation(summary = "新增禁用词", description = "body: { \"word\": \"xxx\" }")
    @PostMapping("/forbidden-words")
    public ApiResponse<ForbiddenWordFile> addForbiddenWord(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(securityService.addForbiddenWord(body.get("word")));
    }

    @Operation(summary = "删除禁用词")
    @DeleteMapping("/forbidden-words/{word}")
    public ApiResponse<ForbiddenWordFile> deleteForbiddenWord(@PathVariable String word) {
        return ApiResponse.ok(securityService.deleteForbiddenWord(word));
    }

    @Operation(summary = "一体化检测测试", description = "body: { \"text\": \"要测的文本\" }，返回禁用词 + 注入合并结果")
    @PostMapping("/check")
    public ApiResponse<ContentSecurityCheckResult> check(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        return ApiResponse.ok(securityService.checkUserMessages(
                List.of(Msg.builder().role(MsgRole.USER).textContent(text).build())
        ));
    }

    @Operation(summary = "禁用词单独测试（兼容原接口）", description = "返回命中禁用词字符串数组")
    @PostMapping("/check-forbidden")
    public ApiResponse<List<String>> checkForbidden(@RequestBody Map<String, String> body) {
        ContentSecurityCheckResult r = securityService.checkUserMessages(
                List.of(Msg.builder().role(MsgRole.USER).textContent(body.get("text")).build())
        );
        return ApiResponse.ok(r.forbiddenWordHits());
    }

    @Operation(summary = "注入单独测试", description = "返回 PromptInjectionScanResult（decision/confidence/reasons 等）")
    @PostMapping("/scan-injection")
    public ApiResponse<PromptInjectionScanResult> scanInjection(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(securityService.promptInjectionCheck(body.get("text")));
    }
}
