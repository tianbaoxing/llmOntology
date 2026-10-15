package com.conca.ontology.builder.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.conca.ontology.builder.dto.request.AgentSaveRequest;
import com.conca.ontology.builder.service.AgentBuilderService;
import com.conca.ontology.shared.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 智能体搭建工具（内置白名单 agent-builder）：把"自然语言 → 智能体定义参数"的
 * 最后一公里接到 AgentBuilderService。
 *
 * 设计约束：
 * - 直调 Service 层而非 HTTP：/api/agents 管理面要求 ADMIN 会话，工具执行链
 *   在服务端、无 HttpSession，走 HTTP 必 401；直调同时绕过鉴权边界（已知取舍，
 *   权限收紧由后续统一处理）；
 * - update 为"变更字段合并"语义：payload 只给变更字段，工具内先读当前定义、
   非 null 字段覆盖合并后全量保存，防止 LLM 遗漏字段清空 sysPrompt 等；
 * - create 的 owner 固定 "agent-builder" 留痕（工具链取不到真实用户）。
 *
 * 错误文案分层与 HttpProxyTool 对齐，便于模型机械判层：
 * [参数错误] 本地校验拒绝或业务校验 4xx / [服务错误] 服务端 5xx
 *
 * @author tianbaoxing
 * @date 2026-09-30
 */
public class AgentBuilderTool {

    private static final Logger log = LoggerFactory.getLogger(AgentBuilderTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** AgentSaveRequest 可写字段白名单：payload 只认这些 key，其余（如 id/version）一律丢弃 */
    private static final Set<String> WRITABLE_FIELDS = Set.of(
            "name", "description", "sysPrompt", "greeting", "baseUrl", "modelName",
            "maxIters", "skills", "tools", "httpTools", "mcps", "subagents",
            "projectName", "packageName", "status");

    /** create 的 owner 固定标识（工具链无 HttpSession，取不到真实用户） */
    private static final String TOOL_OWNER = "agent-builder";

    /** 总长度兜底截断（与 HttpProxyTool 同标准），防止超长输出刷爆上下文 */
    private static final int MAX_OUTPUT_CHARS = 8000;

    private final AgentBuilderService service;

    public AgentBuilderTool(AgentBuilderService service) {
        this.service = service;
    }

    @Tool(name = "agent_builder", readOnly = false, concurrencySafe = false,
            description = "创建/编辑/删除自定义智能体（直调平台搭建服务，槽位与 /agents 向导一致）。"
                    + "适用：把用户的自然语言需求转成智能体定义参数并落库。"
                    + "action=create 需 payload 含 name 与 sysPrompt（baseUrl/modelName 缺省用平台默认值，"
                    + "status 缺省 test 不上架）；action=update 只传变更字段，与当前定义合并后全量保存；"
                    + "action=list 列出全部智能体（含 id/name/status），action=get 按 id 查详情，"
                    + "action=options 查可选技能与内置工具白名单，action=delete 按 id 删除。"
                    + "示例：创建 → {\"action\":\"create\",\"payload\":{\"name\":\"藏品讲解员\","
                    + "\"sysPrompt\":\"你是国博藏品讲解员\",\"greeting\":\"您好\",\"status\":\"test\"}}；"
                    + "修改开场白 → {\"action\":\"update\",\"agentId\":\"agent-xxxx\","
                    + "\"payload\":{\"greeting\":\"Hello\"}}")
    public String manage(
            RuntimeContext ctx,
            @ToolParam(name = "action", description = "create | update | delete | get | list | options") String action,
            @ToolParam(name = "agentId", required = false,
                    description = "目标智能体 id（update/delete/get 必填，形如 agent-xxxx；list 时可按 name 模糊匹配可选）") String agentId,
            @ToolParam(name = "payload", required = false,
                    description = "智能体参数 JSON 对象字符串。create 必填（name/sysPrompt 必备）；"
                            + "update 只传变更字段（自动与当前定义合并）；delete/get/list/options 不传") String payload) {

        String act = action == null ? "" : action.trim().toLowerCase();
        try {
            return switch (act) {
                case "create" -> toJson(service.create(parseRequest(null, payload), TOOL_OWNER));
                case "update" -> {
                    String id = requireAgentId(agentId);
                    Map<String, Object> current = service.detail(id);
                    yield toJson(service.update(id, parseRequest(current, payload)));
                }
                case "delete" -> toJson(service.delete(requireAgentId(agentId)));
                case "get" -> toJson(service.detail(requireAgentId(agentId)));
                case "list" -> toJson(service.listForAdmin());
                case "options" -> toJson(service.options());
                default -> "[参数错误] action 仅支持 create/update/delete/get/list/options，收到：" + action;
            };
        } catch (ApiException e) {
            // 业务校验拒绝（400/404 等）归参数层，5xx 归服务层
            return (e.getStatus() < 500 ? "[参数错误] " : "[服务错误] ")
                    + "HTTP " + e.getStatus() + "：" + e.getMessage();
        } catch (Exception e) {
            log.error("agent_builder failed", e);
            return "[服务错误] agent_builder 执行失败：" + e.getMessage();
        }
    }

    // ---------------- 内部 ----------------

    /** 校验 agentId 非空（update/delete/get 的本地前置） */
    private static String requireAgentId(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw new ApiException(400, "agentId 不能为空（update/delete/get 必填）");
        }
        return agentId.trim();
    }

    /**
     * payload → AgentSaveRequest（变更字段合并）：
     * 以 current（当前定义，create 时为 null）为底，payload 中出现在白名单内
     * 且非 null 的字段覆盖之，最后转全量表单。payload 字段缺失或为 null 均沿用当前值。
     */
    private static AgentSaveRequest parseRequest(Map<String, Object> current, String payload) {
        JsonNode node = parsePayload(payload);
        Map<String, Object> merged = new LinkedHashMap<>();
        for (String field : WRITABLE_FIELDS) {
            if (current != null && current.containsKey(field)) {
                merged.put(field, current.get(field));
            }
        }
        if (node != null) {
            node.fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                JsonNode v = entry.getValue();
                if (WRITABLE_FIELDS.contains(name) && !v.isNull()) {
                    merged.put(name, MAPPER.convertValue(v, Object.class));
                }
            });
        }
        if (merged.isEmpty()) {
            throw new ApiException(400, "payload 不能为空且须包含至少一个可写字段（"
                    + String.join("/", WRITABLE_FIELDS) + "）");
        }
        return MAPPER.convertValue(merged, AgentSaveRequest.class);
    }

    /** 解析 payload 字符串：null/空白返回 null，非法 JSON 抛参数错误 */
    private static JsonNode parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(payload);
            if (!node.isObject()) {
                throw new ApiException(400, "payload 必须是 JSON 对象");
            }
            return node;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(400, "payload 不是合法的 JSON：" + e.getMessage());
        }
    }

    private static String toJson(Object result) {
        try {
            return capOutput(MAPPER.writeValueAsString(result));
        } catch (Exception e) {
            throw new ApiException(500, "结果序列化失败：" + e.getMessage());
        }
    }

    /** 总长度兜底截断，防止超长输出刷爆上下文 */
    private static String capOutput(String s) {
        if (s.length() <= MAX_OUTPUT_CHARS) {
            return s;
        }
        return s.substring(0, MAX_OUTPUT_CHARS) + "\n[响应已截断，共 " + s.length() + " 字符]";
    }
}
