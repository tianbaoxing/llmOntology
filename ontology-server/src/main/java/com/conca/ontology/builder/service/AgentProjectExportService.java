package com.conca.ontology.builder.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import com.conca.agentkit.AgentDefinition;
import com.conca.ontology.shared.web.ApiException;

/**
 * L2 导出工程渲染器：classpath 模板树（export-template/）→ 占位符渲染 → zip 打包。
 *
 * 模板约定（目录位于 ontology-server/src/main/resources/export-template/）：
 * - 静态文件（mvnw/.mvn/静态页）原样复制；动态文件以 {{PROJECT_NAME}}、{{PACKAGE_NAME}}、
 *   {{PACKAGE_PATH}}、{{AGENTSCOPE_VERSION}} 占位，导出时替换；.jar 二进制跳过渲染
 * - src/main/java/_package_/ 目录名渲染时展开为包路径（com/xxx/yyy）
 * - application.yml 不走模板：由定义槽位现场生成（提示词块标量 + 列表配置化，
 *   密钥以 ${AGENT_API_KEY:} 占位，不随工程携带）
 * - workspace/（skills、subagents）自智能体工作区原样拷贝，data/state 由导出工程运行时生成
 *
 * @author tianbaoxing
 * @date 2026-09-29
 */
@Service
public class AgentProjectExportService {

    private static final String TEMPLATE_ROOT = "export-template";
    /** 模板中的包路径占位目录名（src/main/java/_package_） */
    private static final String PACKAGE_DIR_TOKEN = "_package_";
    private static final String DEFAULT_PACKAGE = "com.agentexport";

    private final String agentscopeVersion;

    public AgentProjectExportService(
            @Value("${app.agents.export.agentscope-version:2.0.3}") String agentscopeVersion) {
        this.agentscopeVersion = agentscopeVersion;
    }

    /** 导出产物：临时 zip（流式下载后即删）+ 下载文件名（<projectName>.zip） */
    public record ExportedProject(Path zip, String filename) {}

    public ExportedProject export(AgentDefinition def, Path workspace) {
        String projectName = resolveProjectName(def);
        String packageName = def.packageName().isBlank() ? DEFAULT_PACKAGE : def.packageName();
        Map<String, String> tokens = Map.of(
                "{{PROJECT_NAME}}", projectName,
                "{{PACKAGE_NAME}}", packageName,
                "{{PACKAGE_PATH}}", packageName.replace('.', '/'),
                "{{AGENTSCOPE_VERSION}}", agentscopeVersion);
        try {
            Path zip = Files.createTempFile("agent-project-" + def.id() + "-", ".zip");
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
                copyTemplates(out, projectName, tokens);
                writeEntry(out, projectName + "/src/main/resources/application.yml",
                        renderApplicationYml(def, projectName).getBytes(StandardCharsets.UTF_8));
                copyWorkspaceDir(out, workspace.resolve("skills"), projectName + "/workspace/skills");
                copyWorkspaceDir(out, workspace.resolve("subagents"), projectName + "/workspace/subagents");
            }
            return new ExportedProject(zip, projectName + ".zip");
        } catch (IOException e) {
            throw new ApiException(500, "导出工程生成失败：" + e.getMessage());
        }
    }

    private static String resolveProjectName(AgentDefinition def) {
        return def.projectName().isBlank() ? "agent-" + def.id() + "-project" : def.projectName();
    }

    // ---------------- 模板树 ----------------

    /**
     * 递归复制 classpath 模板树。资源 URL 定位：模板根目录名在部署路径中唯一
     * （jar 内 BOOT-INF/classes/export-template/ 或展开 target/classes/export-template/），
     * 以 indexOf 截取相对路径即可，无需区分两种部署形态。
     */
    private void copyTemplates(ZipOutputStream out, String projectName, Map<String, String> tokens)
            throws IOException {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        for (Resource r : resolver.getResources("classpath*:" + TEMPLATE_ROOT + "/**")) {
            String url = r.getURL().toExternalForm();
            if (url.endsWith("/")) {
                continue; // 目录资源（展开部署形态出现），条目由文件路径隐含
            }
            int idx = url.indexOf(TEMPLATE_ROOT + "/");
            if (idx < 0) {
                continue; // 防御：非模板根下的资源不应出现
            }
            String rel = url.substring(idx + TEMPLATE_ROOT.length() + 1);
            String name = rel.substring(rel.lastIndexOf('/') + 1);
            byte[] bytes;
            try (var in = r.getInputStream()) {
                bytes = in.readAllBytes();
            }
            // 二进制（maven-wrapper.jar）跳过渲染；其余文本资源占位符替换（无占位符 = 原样）
            if (!name.endsWith(".jar")) {
                String text = new String(bytes, StandardCharsets.UTF_8);
                for (Map.Entry<String, String> e : tokens.entrySet()) {
                    text = text.replace(e.getKey(), e.getValue());
                }
                bytes = text.getBytes(StandardCharsets.UTF_8);
            }
            StringBuilder entry = new StringBuilder(projectName);
            for (String seg : rel.split("/")) {
                entry.append('/').append(PACKAGE_DIR_TOKEN.equals(seg)
                        ? tokens.get("{{PACKAGE_PATH}}") : seg);
            }
            writeEntry(out, entry.toString(), bytes);
        }
    }

    // ---------------- workspace（skills / subagents 原样拷贝） ----------------

    private static void copyWorkspaceDir(ZipOutputStream out, Path dir, String zipDir) throws IOException {
        // 空目录也建条目：框架加载 skills/subagents 前有 isDirectory 检查，保持工作区骨架完整
        writeDirEntry(out, zipDir);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted().skip(1).toList()) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                if (Files.isDirectory(p)) {
                    writeDirEntry(out, zipDir + "/" + rel);
                } else {
                    writeEntry(out, zipDir + "/" + rel, Files.readAllBytes(p));
                }
            }
        }
    }

    private static void writeEntry(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    private static void writeDirEntry(ZipOutputStream out, String name) throws IOException {
        out.putNextEntry(new ZipEntry(name + "/"));
        out.closeEntry();
    }

    // ---------------- application.yml 现场生成 ----------------

    /** 定义槽位 → application.yml（agent.* 配置节）；密钥以 ${AGENT_API_KEY:} 占位 */
    private String renderApplicationYml(AgentDefinition def, String projectName) {
        StringBuilder y = new StringBuilder();
        y.append("server:\n");
        y.append("  port: 18080\n");
        y.append("\n");
        y.append("spring:\n");
        y.append("  application:\n");
        y.append("    name: ").append(quote(projectName)).append('\n');
        y.append("\n");
        y.append("agent:\n");
        y.append("  name: ").append(quote(def.name())).append('\n');
        y.append("  description: ").append(quote(def.description())).append('\n');
        y.append("  greeting: ").append(quote(def.greeting())).append('\n');
        y.append("  sys-prompt: |\n");
        for (String line : effectivePrompt(def).split("\n", -1)) {
            y.append("    ").append(line).append('\n');
        }
        y.append("  # 0 = 默认 6，上限 20（AgentScopeConfig 启动时收口）\n");
        y.append("  max-iters: ").append(def.maxIters()).append('\n');
        y.append("  workspace-dir: workspace\n");
        y.append("  state-dir: data/state\n");
        y.append("  model:\n");
        y.append("    base-url: ").append(quote(def.model() == null ? "" : def.model().baseUrl())).append('\n');
        y.append("    model-name: ").append(quote(def.model() == null ? "" : def.model().modelName())).append('\n');
        y.append("    # 密钥不随工程导出：经环境变量 AGENT_API_KEY 注入，未设置时启动失败\n");
        y.append("    api-key: ${AGENT_API_KEY:}\n");
        y.append("  # HTTP 工具（端点固定，agent 只传 path 且受 path-prefix 白名单约束）\n");
        if (def.httpTools().isEmpty()) {
            y.append("  http-tools: []\n");
        } else {
            y.append("  http-tools:\n");
            for (AgentDefinition.HttpToolConfig t : def.httpTools()) {
                y.append("    - name: ").append(quote(t.name())).append('\n');
                y.append("      description: ").append(quote(t.description())).append('\n');
                y.append("      endpoint: ").append(quote(t.endpoint())).append('\n');
                y.append("      path-prefix: ").append(quote(t.pathPrefix())).append('\n');
            }
        }
        y.append("  # MCP 外接（Streamable HTTP 优先，失败自动退回 SSE）\n");
        if (def.mcps().isEmpty()) {
            y.append("  mcps: []\n");
        } else {
            y.append("  mcps:\n");
            for (AgentDefinition.McpConfig m : def.mcps()) {
                y.append("    - name: ").append(quote(m.name())).append('\n');
                y.append("      url: ").append(quote(m.url())).append('\n');
                y.append("      transport: ").append(quote(m.transport())).append('\n');
            }
        }
        return y.toString();
    }

    /** 与平台 AgentBootstrapper 同语义：空提示词回退最小可用中文提示词（导出时固化） */
    private static String effectivePrompt(AgentDefinition def) {
        if (!def.sysPrompt().isBlank()) {
            return def.sysPrompt();
        }
        return "你是「" + def.name() + "」。请用中文回答用户问题，回答需简洁、准确、有条理。"
                + "当问题涉及事实信息而不确定时，如实说明，不要编造。";
    }

    /** 单行标量：YAML 兼容 JSON 双引号转义，并压平换行防结构逃逸 */
    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.replaceAll("[\\r\\n]", " ").toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                default -> sb.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return sb.append('"').toString();
    }
}
