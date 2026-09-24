package com.conca.ontology.builder.model;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.conca.agentkit.AgentDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.conca.ontology.shared.web.ApiException;

/**
 * 智能体定义存储：data/agents/{id}/agent.json（与平台 data/*.json 同风格：文件即库）。
 * 目录布局：data/agents/{id}/ 下 agent.json + workspace/（skills、subagents）+ state/。
 * 写入原子（tmp + move），列表按更新时间倒序。
 */
@Service
public class AgentDefinitionStore {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinitionStore.class);
    /** id 即目录名/agent 名，先挡路径穿越与非法字符 */
    static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,38}$");
    private static final String AGENT_JSON = "agent.json";

    private final Path root;
    private final ObjectMapper mapper;
    private final ObjectWriter writer;
    private final ObjectReader reader;

    public AgentDefinitionStore(@Value("${app.agents.dir:data/agents}") String dir, ObjectMapper mapper) {
        this.root = Path.of(dir);
        this.mapper = mapper;
        this.writer = mapper.writerFor(AgentDefinition.class)
                .with(SerializationFeature.INDENT_OUTPUT);
        this.reader = mapper.readerFor(AgentDefinition.class);
    }

    /** 保存（新建或覆盖整份定义；workspace 布局由 AgentWorkspaceService 负责） */
    public void save(AgentDefinition def) {
        validateId(def.id());
        Path dir = root.resolve(def.id());
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(AGENT_JSON);
            Path tmp = Files.createTempFile(dir, "agent-", ".tmp");
            Files.write(tmp, writer.writeValueAsBytes(def));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ApiException(500, "智能体定义写入失败：" + e.getMessage());
        }
        log.info("智能体定义已保存：{}（version={}）", def.id(), def.version());
    }

    /** 读取单份定义；不存在返回空 */
    public AgentDefinition find(String id) {
        validateId(id);
        Path file = root.resolve(id).resolve(AGENT_JSON);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return reader.readValue(Files.readAllBytes(file));
        } catch (IOException e) {
            log.warn("智能体定义读取失败（{}）：{}", id, e.getMessage());
            return null;
        }
    }

    /** 读取单份定义；不存在抛 404 */
    public AgentDefinition require(String id) {
        AgentDefinition def = find(id);
        if (def == null) {
            throw new ApiException(404, "智能体不存在：" + id);
        }
        return def;
    }

    public boolean exists(String id) {
        return find(id) != null;
    }

    /** 全量列表（updateTime 倒序；调用方负责脱敏） */
    public List<AgentDefinition> list() {
        List<AgentDefinition> result = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                AgentDefinition def = find(dir.getFileName().toString());
                if (def != null) {
                    result.add(def);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        result.sort(Comparator.comparing(AgentDefinition::updatedAt, Comparator.reverseOrder()));
        return result;
    }

    /** 删除定义与整个数据目录（workspace/state 随目录整体清理） */
    public void delete(String id) {
        validateId(id);
        Path dir = root.resolve(id);
        if (!Files.isDirectory(dir)) {
            throw new ApiException(404, "智能体不存在：" + id);
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("清理智能体目录失败（{}）：{}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            throw new ApiException(500, "智能体目录删除失败：" + e.getMessage());
        }
        log.info("智能体已删除：{}", id);
    }

    /** id 对应的数据根目录（workspace/state 均在其下） */
    public Path dirOf(String id) {
        validateId(id);
        return root.resolve(id);
    }

    static void validateId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new ApiException(400, "智能体 ID 非法：仅允许小写字母/数字/连字符，长度 1-39，以字母或数字开头");
        }
    }

    /** 粗读 agent.json 原文（调试/导出校验用） */
    String rawJson(String id) {
        try {
            return Files.readString(root.resolve(id).resolve(AGENT_JSON), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** mapper 复用出口（BuilderService 组装响应用） */
    ObjectMapper mapper() {
        return mapper;
    }
}
