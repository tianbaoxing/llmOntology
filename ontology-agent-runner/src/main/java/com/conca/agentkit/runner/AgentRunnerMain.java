package com.conca.agentkit.runner;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import com.conca.agentkit.AgentBootstrapper;
import com.conca.agentkit.AgentDefinition;
import com.conca.agentkit.AgentEventCodec;
import com.conca.agentkit.AgentPackageCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import reactor.core.scheduler.Schedulers;

/**
 * 导出智能体独立运行入口：java -jar agent-{name}.jar [--port 18080]。
 *
 * 启动流程（设计文档步骤 10）：classpath 读内嵌 agent-package.zip → sha256 校验解压 →
 * Bootstrapper 装配（密钥取环境变量 AGENT_API_KEY，${VAR} 占位符经 System::getenv 解析）→
 * JDK HttpServer 起服务：GET /（极简聊天页）+ GET /meta（名称/描述/开场白）+ POST /chat（SSE，
 * 事件契约与平台 chat 接口完全一致，由 AgentEventCodec 保证）。
 */
public final class AgentRunnerMain {

    private static final String PACKAGE_ENTRY = "agent-package.zip";
    private static final int DEFAULT_PORT = 18080;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        int port = parsePort(args);
        Path packageZip = extractEmbeddedPackage();
        Path base = Files.createTempDirectory("agent-runner-");
        AgentPackageCodec.PackageBundle bundle = AgentPackageCodec.read(packageZip, base.resolve("pkg"));
        AgentDefinition def = bundle.definition();

        String apiKey = System.getenv("AGENT_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[参数错误] 未设置环境变量 AGENT_API_KEY（模型密钥不打包进 jar，独立运行需通过环境变量注入）");
            System.exit(1);
            return;
        }
        AgentBootstrapper.BuiltAgent built = AgentBootstrapper.build(
                def, bundle.workspaceRoot(), base.resolve("state"),
                apiKey, List.of(), List.of(), System::getenv);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/", exchange -> route(exchange, def, built));
        server.start();
        System.out.println("智能体「" + def.name() + "」已启动：http://127.0.0.1:" + port
                + "（停止：Ctrl+C；密钥来自环境变量 AGENT_API_KEY）");
        Runtime.getRuntime().addShutdownHook(new Thread(built::close));
    }

    /** --port N 覆盖默认端口；非法值按默认处理（独立场景保持启动优先） */
    private static int parsePort(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--port".equals(args[i])) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException ignored) {
                    System.err.println("[参数错误] --port 值非法（" + args[i + 1] + "），使用默认端口 " + DEFAULT_PORT);
                }
            }
        }
        return DEFAULT_PORT;
    }

    /** classpath 内嵌包释放到临时文件（zip 校验在 AgentPackageCodec.read 内完成） */
    private static Path extractEmbeddedPackage() throws IOException {
        var in = AgentRunnerMain.class.getClassLoader().getResourceAsStream(PACKAGE_ENTRY);
        if (in == null) {
            System.err.println("[参数错误] jar 内未找到 " + PACKAGE_ENTRY + "（请通过平台「导出 jar」生成，手工拼装不受支持）");
            System.exit(1);
        }
        Path tmp = Files.createTempFile("agent-package-", ".zip");
        try (in; OutputStream out = Files.newOutputStream(tmp)) {
            in.transferTo(out);
        }
        return tmp;
    }

    private static void route(HttpExchange ex, AgentDefinition def, AgentBootstrapper.BuiltAgent built) throws IOException {
        String path = ex.getRequestURI().getPath();
        try {
            if ("/".equals(path) || "/index.html".equals(path)) {
                respond(ex, 200, "text/html; charset=utf-8", RunnerChatPage.html().getBytes(StandardCharsets.UTF_8));
            } else if ("/meta".equals(path)) {
                String json = MAPPER.writeValueAsString(java.util.Map.of(
                        "name", def.name(),
                        "description", def.description(),
                        "greeting", def.greeting() == null ? "" : def.greeting()));
                respond(ex, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
            } else if ("/chat".equals(path) && "POST".equals(ex.getRequestMethod())) {
                chat(ex, def, built);
            } else {
                respond(ex, 404, "text/plain; charset=utf-8", "404 Not Found".getBytes(StandardCharsets.UTF_8));
            }
        } finally {
            ex.close();
        }
    }

    /** SSE 问答：session 帧（会话 id 分配/沿用）→ 事件帧（AgentEventCodec）→ done/error 收尾 */
    private static void chat(HttpExchange ex, AgentDefinition def, AgentBootstrapper.BuiltAgent built) throws IOException {
        JsonNode body;
        try {
            body = MAPPER.readTree(ex.getRequestBody().readAllBytes());
        } catch (Exception e) {
            respond(ex, 400, "application/json; charset=utf-8",
                    "{\"message\":\"[参数错误] 请求体不是合法 JSON\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String message = body.path("message").asText("");
        if (message.isBlank()) {
            respond(ex, 400, "application/json; charset=utf-8",
                    "{\"message\":\"[参数错误] message 不能为空\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String session = body.path("sessionId").asText("");
        if (session.isBlank()) {
            session = "run-" + UUID.randomUUID();
        }

        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream out = ex.getResponseBody()) {
            writeFrame(out, "session", "{\"sessionId\":\"" + session + "\"}");
            try {
                Msg userMsg = Msg.builder().role(MsgRole.USER).textContent(message).build();
                RuntimeContext ctx = RuntimeContext.builder().userId("runner").sessionId(session).build();
                // handler 线程内同步迭代事件流（单会话顺序收尾，与平台 doFinally 语义一致）
                built.agent().streamEvents(userMsg, ctx)
                        .subscribeOn(Schedulers.boundedElastic())
                        .toIterable()
                        .forEach(event -> {
                            for (AgentEventCodec.Frame f : AgentEventCodec.frames(event)) {
                                writeFrame(out, f.event(), f.data());
                            }
                        });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                writeFrame(out, "error", "{\"message\":" + quote(msg) + "}");
            }
            out.flush();
        }
    }

    private static void writeFrame(OutputStream out, String event, String data) {
        try {
            out.write(("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
            // 客户端断开：停止写入即可（toIterable 的消费会随流取消）
        }
    }

    /** 极简 JSON 字符串转义（避免引入更多依赖；错误文案回传专用） */
    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static void respond(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private AgentRunnerMain() {
    }
}
