package com.conca.ontology.agent.middleware;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 文件边界拦截中间件（防线二，服务端 fail-closed 兜底，与 harness filesystem 配置互为独立）：
 *
 * 所有文件类工具与 execute 命令的目标路径必须落在数据根（data/）内，界外一律拒绝——
 * 工程代码、配置、系统目录对智能体不可达，封堵"诱导删除/改写项目文件"类破坏。
 *
 * - onActing：read_file/write_file/edit_file/list_files/glob_files/grep_files 的 path、
 *   execute 的 working_directory 硬判；execute 的 command 文本 best-effort 提取
 *   绝对路径与 ../ 逃逸链判定。被拒调用不执行，合成 DENIED 工具结果
 *   （写入上下文 + 流式事件，RolePermissionMiddleware 同款模式），模型收到后自行换路。
 * - onSystemPrompt：追加边界声明块，减少模型无效尝试。
 * - 判定算法：词法 normalize 消解 ../ 穿越；自目标向上取最近存在祖先 toRealPath
 *   消解符号链接与 Windows 8.3 短名后拼回尾部（不存在的目标以祖先真实位置为准）。
 *
 * order()=3：位于 RolePermissionMiddleware（2）外层，边界判定先于角色判定。
 * 实现 HarnessRuntimeMiddleware 标记接口：harness 派生子代理时不复制本中间件
 * （子代理文件边界依赖防线一 harness ROOTED 配置）。
 *
 * fail-closed 说明：path/working_directory 缺失按框架默认语义（工作区自身=界内）放行；
 * 非法路径（InvalidPathException）与真实路径解析失败一律按界外拒绝。
 * command 文本提取为 best-effort（正则可被构造绕过，兜底依赖防线一）。
 *
 * @author tianbaoxing
 * @date 2026-10-16
 */
public class PathBoundaryMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(PathBoundaryMiddleware.class);

    private static final String TOOL_EXECUTE = "execute";

    /** 文件类工具（path 参数）：目标在数据根外一律拒绝——读=泄露、写/删=破坏、列/搜=踩点 */
    private static final Set<String> PATH_TOOLS = Set.of(
            "read_file", "write_file", "edit_file", "list_files", "glob_files", "grep_files");

    /**
     * execute command 中的路径提取（best-effort）：
     * 绝对路径=Windows 盘符（含正斜杠变体）与 UNC 共享；相对逃逸=../ 穿越链（相对工作目录向外）。
     */
    private static final Pattern ABS_PATH = Pattern.compile("(?:[A-Za-z]:[\\\\/]|\\\\\\\\)[^\\s\"'*?<>|]+");
    private static final Pattern REL_ESCAPE = Pattern.compile("(?:\\.\\.[\\\\/])+[^\\s\"'*?<>|]*");

    private final Path dataRoot;
    private final Path workspace;

    /**
     * @param dataRootCfg 数据根配置值（相对 cwd 解析）；目录必须已存在，fail-fast
     * @param workspace   当前智能体工作区；必须位于数据根内（词法判定），fail-fast
     */
    public PathBoundaryMiddleware(String dataRootCfg, Path workspace) {
        //用户可以看到/data/agent-workspace信息，现在这个信息禁止查看，只允许查看/data/agent-workspace/files目录
        String dataRoot = dataRootCfg.trim()+File.separator+"files";
        Path root = Path.of(dataRoot.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("[配置错误] 数据根目录不存在或不是目录：" + root
                    + "（app.agent.data-root=" + dataRootCfg + "），请先创建该目录");
        }
        Path ws = workspace.toAbsolutePath().normalize();
        if (!ws.startsWith(root)) {
            throw new IllegalStateException("[配置错误] 工作区不在数据根内：workspace=" + ws
                    + "，dataRoot=" + root + "，请检查 app.agent.workspace-dir 与 app.agent.data-root 配置");
        }
        try {
            this.dataRoot = root.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("[配置错误] 数据根真实路径解析失败：" + root + "（" + e.getMessage() + "）");
        }
        this.workspace = ws;
    }

    /** 数据根（已 toRealPath 消解），供防线一（harness filesystem spec）复用 */
    public Path dataRoot() {
        return dataRoot;
    }

    @Override
    public int order() {
        // 3 = RolePermissionMiddleware（2）外层：文件边界判定先于角色权限
        return 3;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        String base = currentPrompt != null ? currentPrompt : "";
        StringBuilder sb = new StringBuilder(base);
        if (!base.isEmpty() && !base.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append("\n<file_boundary>\n")
          .append("文件操作边界：文件工具与终端命令仅允许访问数据目录（").append(dataRoot)
          .append("）及其子目录；访问项目源码、配置文件或其他系统目录的调用一律会被系统拒绝，请勿尝试。\n")
          .append("</file_boundary>");
        return Mono.just(sb.toString());
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        if (input == null || input.toolCalls() == null || input.toolCalls().isEmpty()) {
            return next.apply(input);
        }
        List<ToolUseBlock> allowed = new ArrayList<>();
        List<ToolUseBlock> denied = new ArrayList<>();
        for (ToolUseBlock call : input.toolCalls()) {
            if (isDenied(call)) {
                denied.add(call);
            } else {
                allowed.add(call);
            }
        }
        if (denied.isEmpty()) {
            return next.apply(input);
        }
        log.warn("文件边界拦截：user={} denied={}",
                ctx != null ? ctx.getUserId() : null,
                denied.stream().map(c -> c.getName() + argBrief(c)).toList());

        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        String replyId = state != null ? state.getReplyId() : null;
        String agentName = agent != null && agent.getName() != null ? agent.getName() : "agent";

        // Deferred so the context mutation + events run once, at subscription time.
        Flux<AgentEvent> deniedFlux = Flux.defer(() -> {
            List<AgentEvent> events = new ArrayList<>();
            for (ToolUseBlock call : denied) {
                String message = denyMessage(call);
                ToolResultBlock result = ToolResultBlock.text(message)
                        .withIdAndName(call.getId(), call.getName())
                        .withState(ToolResultState.DENIED);
                if (state != null) {
                    Msg msg = ToolResultMessageBuilder.buildToolResultMsg(result, call, agentName);
                    state.contextMutable().add(msg);
                }
                events.add(new ToolResultStartEvent(replyId, call.getId(), call.getName()));
                events.add(new ToolResultTextDeltaEvent(replyId, call.getId(), call.getName(), message));
                events.add(new ToolResultEndEvent(replyId, call.getId(), call.getName(),
                        ToolResultState.DENIED));
            }
            return Flux.fromIterable(events);
        });

        if (allowed.isEmpty()) {
            return deniedFlux;
        }
        return deniedFlux.concatWith(next.apply(new ActingInput(allowed)));
    }

    /**
     * 两类判定（全部纯内存路径运算，无 IO 副作用）：
     * 文件类工具的 path；execute 的 working_directory（硬判）与 command 文本（best-effort）。
     */
    private boolean isDenied(ToolUseBlock call) {
        String name = call.getName();
        if (name == null) {
            return false;
        }
        if (PATH_TOOLS.contains(name)) {
            return pathOutside(stringArg(call, "path"));
        }
        if (TOOL_EXECUTE.equals(name)) {
            return executeOutside(call);
        }
        return false;
    }

    private String denyMessage(ToolUseBlock call) {
        if (TOOL_EXECUTE.equals(call.getName())) {
            return "路径越界：命令涉及数据目录之外的路径，该调用已被系统拒绝。"
                    + "请仅操作 " + dataRoot + " 内的文件。";
        }
        String path = stringArg(call, "path");
        return "路径越界：" + path + " 超出允许范围（仅 " + dataRoot + " 内），该调用已被系统拒绝。";
    }

    /**
     * 目标路径是否在数据根外：
     * null/blank = 框架默认语义（工作区自身，天然界内）放行；
     * 非法路径（InvalidPathException）按界外拒绝（fail-closed）。
     * 相对路径相对工作区解析（与 harness 文件工具语义一致）。
     */
    private boolean pathOutside(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        Path p;
        try {
            p = Path.of(raw.trim());
        } catch (InvalidPathException e) {
            return true;
        }
        Path absolute = (p.isAbsolute() ? p : workspace.resolve(p)).normalize();
        return !withinDataRoot(absolute);
    }

    /**
     * 绝对路径（已 normalize）是否在数据根内：
     * 自目标向上取最近存在的祖先 toRealPath（消解符号链接与 Windows 8.3 短名），
     * 拼回不存在的尾部段——待写新文件以其最近存在祖先的真实位置为准，
     * 防"界内符号链接指向界外"的写入逃逸。解析失败按界外拒绝（fail-closed）。
     */
    private boolean withinDataRoot(Path absolute) {
        Path probe = absolute;
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        if (probe == null) {
            // 全链不存在（理论不可达：盘符根/UNC 根总是存在），按词法判定兜底
            return absolute.startsWith(dataRoot);
        }
        try {
            Path real = probe.toRealPath();
            if (probe.getNameCount() < absolute.getNameCount()) {
                real = real.resolve(absolute.subpath(probe.getNameCount(), absolute.getNameCount()));
            }
            return real.startsWith(dataRoot);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * execute 兜底：working_directory 按路径硬判；
     * command 文本 best-effort 提取绝对路径（盘符/UNC）与 ../ 逃逸链，
     * 相对工作区解析后判定（默认工作目录=工作区）。
     */
    private boolean executeOutside(ToolUseBlock call) {
        if (pathOutside(stringArg(call, "working_directory"))) {
            return true;
        }
        String command = stringArg(call, "command");
        if (command == null || command.isBlank()) {
            return false;
        }
        Matcher abs = ABS_PATH.matcher(command);
        while (abs.find()) {
            if (pathOutside(abs.group())) {
                return true;
            }
        }
        Matcher rel = REL_ESCAPE.matcher(command);
        while (rel.find()) {
            if (pathOutside(rel.group())) {
                return true;
            }
        }
        return false;
    }

    /** 从工具入参取字符串值（缺参返回 null，不参与匹配） */
    private static String stringArg(ToolUseBlock call, String key) {
        Map<String, Object> args = call.getInput();
        Object v = args == null ? null : args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String argBrief(ToolUseBlock call) {
        if (TOOL_EXECUTE.equals(call.getName())) {
            return "(" + stringArg(call, "command") + ")";
        }
        return "(" + stringArg(call, "path") + ")";
    }
}
