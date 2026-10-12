package com.conca.ontology.builder.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.conca.agentkit.AgentDefinition;
import com.conca.ontology.builder.entity.AgentDefinitionEntity;
import com.conca.ontology.builder.mapper.AgentDefinitionMapper;
import com.conca.ontology.builder.model.AgentDefinitionConverter;
import com.conca.ontology.shared.db.migration.JsonMigrator;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * data/agents/{id}/agent.json → agent_definition 多行表存量迁移（P5，每智能体一行）。
 * 混合目录源：workspace/、state/ 子目录保留文件系统（框架仍用，AgentDefinitionStore.dirOf 仍以
 * data/agents/{id} 为根），仅定义本体入库；归档也只把各 agent.json rename 为 agent.json.migrated，
 * 目录骨架与其余子目录不动（覆盖 sourcePresent/archive）。
 * 全新部署无默认数据：无 agent.json = 无智能体，对齐原「目录不存在视为空列表」（initDefaults 空实现）。
 */
@Component
@Order(61)
public class AgentDefinitionMigrator implements JsonMigrator {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinitionMigrator.class);
    private static final String AGENT_JSON = "agent.json";
    /** 与 AgentDefinitionStore.ID 同源（目录名即 agentId，先挡非法字符） */
    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,38}$");

    private final ObjectMapper mapper;
    private final AgentDefinitionMapper agentMapper;
    private final String agentsDir;

    public AgentDefinitionMigrator(ObjectMapper mapper, AgentDefinitionMapper agentMapper,
            @Value("${app.agents.dir:data/agents}") String agentsDir) {
        this.mapper = mapper;
        this.agentMapper = agentMapper;
        this.agentsDir = agentsDir;
    }

    @Override
    public String name() {
        return "agent-definition";
    }

    /** 源为混合目录（workspace/state 常驻），以「是否存在至少一份待迁移 agent.json」为准 */
    @Override
    public Path sourceFile() {
        return Path.of(agentsDir).toAbsolutePath().normalize();
    }

    @Override
    public boolean sourcePresent() {
        return !agentJsonFiles().isEmpty();
    }

    @Override
    public boolean targetTableEmpty() {
        return agentMapper.selectCount(null) == 0;
    }

    @Override
    @Transactional
    public void importAll() throws Exception {
        List<Path> files = agentJsonFiles();
        int count = 0;
        for (Path f : files) {
            String dirName = f.getParent().getFileName().toString();
            if (!ID.matcher(dirName).matches()) {
                throw new IllegalStateException("智能体目录名非法: " + dirName);
            }
            AgentDefinition def = mapper.readValue(Files.readAllBytes(f), AgentDefinition.class);
            if (!dirName.equals(def.id())) {
                throw new IllegalStateException("agent.json 内 id 与目录名不一致: " + dirName + " vs " + def.id());
            }
            AgentDefinitionEntity row = AgentDefinitionConverter.toEntity(def);
            agentMapper.insert(row);
            count++;
        }
        log.info("data/agents 导入完成：{} 份智能体定义（workspace/state 目录保留文件系统）", count);
    }

    /** 逐文件精细归档：{id}/agent.json → {id}/agent.json.migrated（workspace/state 不动） */
    @Override
    public void archive() throws IOException {
        for (Path f : agentJsonFiles()) {
            Files.move(f, f.resolveSibling(AGENT_JSON + ".migrated"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 扫描待迁移的 agent.json（按目录名排序，结果快照保证 importAll/archive 看到同一批文件） */
    private List<Path> agentJsonFiles() {
        Path root = sourceFile();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(root)) {
            s.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(d -> d.resolve(AGENT_JSON))
                    .filter(Files::isRegularFile)
                    .forEach(files::add);
        } catch (IOException e) {
            throw new IllegalStateException("扫描 data/agents 目录失败：" + root, e);
        }
        return files;
    }
}
