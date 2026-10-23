package com.conca.ontology.content.api;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conca.ontology.shared.web.ApiResponse;
import com.conca.ontology.content.model.ContentSecurityAllowlistView;
import com.conca.ontology.content.model.ContentSecurityRule;
import com.conca.ontology.content.model.ContentSecurityRulesView;
import com.conca.ontology.content.dto.ContentSecurityCheckResultDTO;
import com.conca.ontology.content.dto.PromptInjectionScanResultDTO;
import com.conca.ontology.content.service.ContentSecurityService;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * @author tianbaoxing
 * @date 2026-09-17
 */
@Tag(name = "Admin-ContentSecurity", description = "内容安全管理：Prompt 注入规则 + allowlist + 扫描测试")
@RestController
@RequestMapping("/api/content-security")
public class ContentSecurityController {

    private final ContentSecurityService securityService;

    public ContentSecurityController(ContentSecurityService securityService) {
        this.securityService = securityService;
    }

    @Operation(summary = "全量规则", description = "读取全部内容安全规则（rule_content_security 表，按声明顺序）")
    @GetMapping("/rules")
    public ApiResponse<ContentSecurityRulesView> getAllRules() {
        return ApiResponse.ok(securityService.loadAllRules());
    }

    @Operation(summary = "新增自定义规则", description = "body = ContentSecurityRule JSON；ID 唯一")
    @PostMapping("/rules")
    public ApiResponse<ContentSecurityRulesView> addRule(@RequestBody ContentSecurityRule rule) {
        return ApiResponse.ok(securityService.addRule(rule));
    }

    @Operation(summary = "删除自定义规则", description = "按 ruleId 删除；内置规则也允许删（删完 reload 生效）")
    @DeleteMapping("/rules/{ruleId}")
    public ApiResponse<ContentSecurityRulesView> deleteRule(@PathVariable String ruleId) {
        return ApiResponse.ok(securityService.deleteRule(ruleId));
    }

    @Operation(summary = "allowlist 规则 ID 数组", description = "读取已加入 allowlist 的规则 ID")
    @GetMapping("/allowlist")
    public ApiResponse<ContentSecurityAllowlistView> getAllowlist() {
        return ApiResponse.ok(securityService.loadAllowlist());
    }

    @Operation(summary = "添加 allowlist", description = "body: { \"ruleId\": \"xxx\" }")
    @PostMapping("/allowlist")
    public ApiResponse<ContentSecurityAllowlistView> addAllowlist(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(securityService.addAllowlist(body.get("ruleId")));
    }

    @Operation(summary = "移出 allowlist")
    @DeleteMapping("/allowlist/{ruleId}")
    public ApiResponse<ContentSecurityAllowlistView> removeAllowlist(@PathVariable String ruleId) {
        return ApiResponse.ok(securityService.removeAllowlist(ruleId));
    }

    @Operation(summary = "一体化检测测试", description = "body: { \"text\": \"要测的文本\" }，返回注入合并结果")
    @PostMapping("/check")
    public ApiResponse<ContentSecurityCheckResultDTO> check(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        return ApiResponse.ok(securityService.checkUserMessages(
                List.of(Msg.builder().role(MsgRole.USER).textContent(text).build())
        ));
    }

    @Operation(summary = "注入单独测试", description = "返回 PromptInjectionScanResultDTO（decision/confidence/reasons 等）")
    @PostMapping("/scan-injection")
    public ApiResponse<PromptInjectionScanResultDTO> scanInjection(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(securityService.promptInjectionCheck(body.get("text")));
    }
}
