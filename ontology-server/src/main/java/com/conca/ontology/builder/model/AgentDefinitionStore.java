package com.conca.ontology.builder.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.conca.agentkit.AgentDefinition;
import com.conca.ontology.builder.converter.AgentDefinitionConverter;
import com.conca.ontology.builder.entity.AgentDefinitionEntity;
import com.conca.ontology.builder.mapper.AgentDefinitionMapper;
import com.conca.ontology.shared.db.MySqlAgentStateStores;
import com.conca.ontology.shared.web.ApiException;

/**
 * 智能体定义存储（MySQL agent_definition，P5 起替代 data/agents/{id}/agent.json 文件即库）：
 * 每智能体一行，行级 CRUD（save 行级 upsert / find 主键查询 / list 更新时间倒序 / delete 删行+清目录）。
 * 目录布局 data/agents/{id}/ 下 workspace/（skills、subagents）+ state/ 仍归文件系统
 * （框架按路径加载，dirOf/AgentWorkspaceService 拼路径不变），目录本身由 workspace/state 落盘时创建。
 */
@Service
public class AgentDefinitionStore {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinitionStore.class);
    /** id 即目录名/agent 名，先挡路径穿越与非法字符 */
    static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,38}$");

    private final Path root;
    private final AgentDefinitionMapper agentMapper;
    private final JdbcTemplate jdbcTemplate;

    public AgentDefinitionStore(@Value("${app.agents.dir:data/agents}") String dir, AgentDefinitionMapper agentMapper,
            JdbcTemplate jdbcTemplate) {
        this.root = Path.of(dir);
        this.agentMapper = agentMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 保存（新建或覆盖整份定义；workspace 布局由 AgentWorkspaceService 负责） */
    public void save(AgentDefinition def) {
        validateId(def.id());
        AgentDefinitionEntity row = AgentDefinitionConverter.toEntity(def);
        if (agentMapper.selectById(row.getAgentId()) == null) {
            agentMapper.insert(row);
        } else {
            agentMapper.updateById(row);
        }
        log.info("智能体定义已保存：{}（version={}）", def.id(), def.version());
    }

    /** 读取单份定义；不存在返回空 */
    public AgentDefinition find(String id) {
        validateId(id);
        AgentDefinitionEntity row = agentMapper.selectById(id);
        return row == null ? null : AgentDefinitionConverter.toModel(row);
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
        for (AgentDefinitionEntity row : agentMapper.selectList(Wrappers.<AgentDefinitionEntity>lambdaQuery()
                .orderByDesc(AgentDefinitionEntity::getUpdatedAt))) {
            result.add(AgentDefinitionConverter.toModel(row));
        }
        return result;
    }

    /**
     * 删除定义、会话状态表与整个数据目录：
     * 先删表行（DB 为准，定义即刻不存在），再清会话表与目录（不可回滚，
     * 失败仅告警——残留为无害垃圾，不阻断删除流程）。
     */
    public void delete(String id) {
        validateId(id);
        if (agentMapper.selectById(id) == null) {
            throw new ApiException(404, "智能体不存在：" + id);
        }
        agentMapper.deleteById(id);
        dropStateTable(id);
        cleanupDir(id);
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

    /** 会话状态表清理（best-effort：表可能尚未建过，DROP 失败仅告警不阻断删除） */
    private void dropStateTable(String id) {
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS `" + MySqlAgentStateStores.DATABASE_NAME + "`.`"
                    + MySqlAgentStateStores.tableNameOf(id) + "`");
        } catch (DataAccessException e) {
            log.warn("清理智能体会话状态表失败（{}）：{}", id, e.getMessage());
        }
    }

    /** 目录清理（best-effort：逐文件删，失败告警；目录不存在直接返回） */
    private void cleanupDir(String id) {
        Path dir = root.resolve(id);
        if (!Files.isDirectory(dir)) {
            return;
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
            log.warn("清理智能体目录失败（{}）：{}", dir, e.getMessage());
        }
    }
}
