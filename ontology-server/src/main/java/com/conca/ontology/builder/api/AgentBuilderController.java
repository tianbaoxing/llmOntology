package com.conca.ontology.builder.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.conca.ontology.builder.dto.request.AgentSaveRequest;
import com.conca.ontology.builder.dto.request.TestMcpRequest;
import com.conca.ontology.builder.dto.request.TestModelRequest;
import com.conca.ontology.builder.service.AgentBuilderService;
import com.conca.ontology.builder.service.AgentProjectExportService;
import com.conca.ontology.shared.web.ApiException;
import com.conca.ontology.shared.web.ApiResponse;
import com.conca.ontology.user.web.SessionUser;

import jakarta.servlet.http.HttpSession;

/**
 * 智能体搭建管理入口（/api/agents，仅 ADMIN；for-chat 登录即可）：
 * GET    /api/agents                    → 管理列表（含密钥配置/实例存活状态）
 * POST   /api/agents                    → 创建（9 槽位表单）
 * GET    /api/agents/options            → 向导选项面（平台技能清单 + 内置工具白名单）
 * GET    /api/agents/for-chat           → chat 页可选清单（登录即可，仅 active）
 * GET    /api/agents/{id}               → 详情（不含密钥）
 * PUT    /api/agents/{id}               → 编辑（version+1 驱使注册表重建；apiKey 留空=沿用）
 * DELETE /api/agents/{id}               → 删除（驱逐实例 → 清定义/工作区/密钥）
 * POST   /api/agents/test-model         → 模型连通性测试（key 只测不存）
 * POST   /api/agents/test-mcp           → MCP 端点握手探测（返回远端工具清单）
 * GET    /api/agents/{id}/export         → L1 导出包下载（zip）
 * GET    /api/agents/{id}/export-project → L2 导出工程下载（AgentScope 2.0 原生源码工程 zip）
 */
@RestController
@RequestMapping("/api/agents")
public class AgentBuilderController {

    private final AgentBuilderService builderService;

    public AgentBuilderController(AgentBuilderService builderService) {
        this.builderService = builderService;
    }

    /** 管理列表（含 hasKey/live 诊断位） */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(HttpSession session) {
        requireAdmin(session);
        return ApiResponse.ok(builderService.listForAdmin());
    }

    /** 向导选项面：平台技能清单（SKILL.md frontmatter）+ 内置工具白名单 */
    @GetMapping("/options")
    public ApiResponse<Map<String, Object>> options(HttpSession session) {
        requireAdmin(session);
        return ApiResponse.ok(builderService.options());
    }

    /** chat 页可选智能体清单（登录即可；仅上架 active，含开场白） */
    @GetMapping("/for-chat")
    public ApiResponse<List<Map<String, Object>>> forChat(HttpSession session) {
        requireLogin(session);
        return ApiResponse.ok(builderService.listForChat());
    }

    /** 创建（id 服务端生成，与目录/agent 名绑定后不可变） */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody AgentSaveRequest request,
            HttpSession session) {
        requireAdmin(session);
        return ApiResponse.ok(builderService.create(request, SessionUser.of(session).userId()));
    }

    /** 详情（定义不含密钥明文，hasKey 标记是否已配置） */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String id, HttpSession session) {
        requireAdmin(session);
        return ApiResponse.ok(builderService.detail(id));
    }

    /** 编辑（version+1 + 注册表驱逐，下次访问重建生效） */
    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable String id,
            @RequestBody AgentSaveRequest request, HttpSession session) {
        requireAdmin(session);
        return ApiResponse.ok(builderService.update(id, request));
    }

    /** 删除（驱逐实例 → 清定义/工作区/状态/密钥） */
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String id, HttpSession session) {
        requireAdmin(session);
        return ApiResponse.ok(builderService.delete(id));
    }

    /** 模型连通性测试：一条最小对话请求，区分 地址错 / key 错 / 模型名错 */
    @PostMapping("/test-model")
    public ApiResponse<Map<String, Object>> testModel(@RequestBody TestModelRequest request,
            HttpSession session) {
        requireAdmin(session);
        return ApiResponse.ok(builderService.testModel(
                request.baseUrl(), request.modelName(), request.apiKey()));
    }

    /** MCP 端点握手探测：返回远端工具清单 */
    @PostMapping("/test-mcp")
    public ApiResponse<Map<String, Object>> testMcp(@RequestBody TestMcpRequest request,
            HttpSession session) {
        requireAdmin(session);
        return ApiResponse.ok(builderService.testMcp(request.url()));
    }

    /** L1 导出包下载（zip：定义导出视图 + workspace + sha256 清单） */
    @GetMapping("/{id}/export")
    public ResponseEntity<StreamingResponseBody> exportZip(@PathVariable String id,
            HttpSession session) {
        requireAdmin(session);
        return streamAttachment(builderService.exportZip(id),
                id + ".zip", MediaType.parseMediaType("application/zip"));
    }

    /** L2 导出工程下载（AgentScope 2.0 原生 Spring Boot 源码工程 zip，导出后可深度二次开发；密钥经 AGENT_API_KEY 注入） */
    @GetMapping("/{id}/export-project")
    public ResponseEntity<StreamingResponseBody> exportProject(@PathVariable String id,
            HttpSession session) {
        requireAdmin(session);
        AgentProjectExportService.ExportedProject project = builderService.exportProject(id);
        return streamAttachment(project.zip(), project.filename(),
                MediaType.parseMediaType("application/zip"));
    }

    // ---------------- 内部 ----------------

    /** 流式回写临时产物，写完即删（工程 zip 可达数十 MB，不整包进内存） */
    private static ResponseEntity<StreamingResponseBody> streamAttachment(Path file, String filename,
            MediaType type) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new ApiException(500, "导出文件读取失败：" + e.getMessage());
        }
        StreamingResponseBody body = out -> {
            try (InputStream in = Files.newInputStream(file)) {
                in.transferTo(out);
            } finally {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // 临时目录残留可接受
                }
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(type)
                .contentLength(size)
                .body(body);
    }

    /** 拦截器已保证登录；此处兜底防御性校验 */
    private static void requireLogin(HttpSession session) {
        if (SessionUser.of(session) == null) {
            throw new ApiException(401, "未登录或会话已过期");
        }
    }

    private static void requireAdmin(HttpSession session) {
        SessionUser u = SessionUser.of(session);
        if (u == null) {
            throw new ApiException(401, "未登录或会话已过期");
        }
        if (!u.isAdmin()) {
            throw new ApiException(403, "仅管理员可管理智能体");
        }
    }
}
