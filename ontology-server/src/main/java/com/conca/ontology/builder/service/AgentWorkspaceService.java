package com.conca.ontology.builder.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.conca.agentkit.AgentDefinition;
import com.conca.ontology.builder.store.AgentDefinitionStore;
import com.conca.ontology.shared.db.MySqlAgentStateStores;
import com.conca.ontology.shared.web.ApiException;

/**
 * 智能体工作区编排：把定义中的 skills（拷贝自平台技能库）与 subagents（frontmatter .md）
 * 落盘到 data/agents/{id}/workspace/（框架固定约定 skills/、subagents/ 目录名不可改）。
 *
 * 拷贝而非引用（projectGlobalSkillsDir 仅支持单目录，无法按 agent 挂技能子集）：
 * 一份拷贝同时解决"多智能体各选各的技能"与"导出包完整性"两个问题；
 * 平台技能库升级不自动同步，编辑智能体时重新拷贝覆盖。
 *
 * @author tianbaoxing
 * @date 2026-09-24
 */
@Service
public class AgentWorkspaceService {

    /** 子 agent 声明文件命名（同时是运行时 agentId） */
    private static final Pattern SUBAGENT_NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final String SKILLS_DIR = "skills";
    private static final String SUBAGENTS_DIR = "subagents";

    private final AgentDefinitionStore store;
    private final Path platformSkillsDir;
    private final JdbcTemplate jdbcTemplate;

    public AgentWorkspaceService(AgentDefinitionStore store,
            @Value("${app.agent.skills-dir:}") String platformSkillsDir,
            JdbcTemplate jdbcTemplate) {
        this.store = store;
        this.platformSkillsDir = (platformSkillsDir == null || platformSkillsDir.isBlank())
                ? null : Path.of(platformSkillsDir);
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 平台技能库目录（向导技能清单/拷贝源） */
    public Path platformSkillsDir() {
        return platformSkillsDir;
    }

    /** 按定义重建工作区（编辑语义：先清空 skills/ 与 subagents/ 再重放，避免残留勾选） */
    public void prepare(AgentDefinition def) {
        Path workspace = workspaceOf(def.id());
        try {
            Files.createDirectories(workspace);
            rebuildDir(workspace.resolve(SKILLS_DIR));
            rebuildDir(workspace.resolve(SUBAGENTS_DIR));
            copySkills(def, workspace.resolve(SKILLS_DIR));
            writeSubagents(def, workspace.resolve(SUBAGENTS_DIR));
        } catch (IOException e) {
            throw new ApiException(500, "智能体工作区构建失败：" + e.getMessage());
        }
    }

    /** workspace 根（skills/、subagents/ 所在；state 独立在 data/agents/{id}/state） */
    public Path workspaceOf(String agentId) {
        return store.dirOf(agentId).resolve("workspace");
    }

    /**
     * 清空试运行会话状态（agent_state_{agentId} 表整表清空）：
     * test 期间的会话都是试运行临时会话，编辑/发布从零开始。
     * 表不存在 = 从未有会话，视为已清空（对齐原「state 目录不存在直接返回」语义）。
     */
    public void clearState(String agentId) {
        store.dirOf(agentId); // 触发 id 校验（表名拼接前置）
        String table = MySqlAgentStateStores.tableNameOf(agentId);
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                Integer.class, MySqlAgentStateStores.DATABASE_NAME, table);
        if (exists == null || exists == 0) {
            return;
        }
        jdbcTemplate.execute("DELETE FROM `" + MySqlAgentStateStores.DATABASE_NAME + "`.`" + table + "`");
    }

    /** 平台技能目录是否存在（存在才允许勾选） */
    public boolean skillExists(String skillId) {
        if (platformSkillsDir == null) {
            return false;
        }
        return Files.isRegularFile(platformSkillsDir.resolve(skillId).resolve("SKILL.md"));
    }

    // ---------------- 内部 ----------------

    /** 清空并重建目录（编辑重放语义） */
    private static void rebuildDir(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 尽力清理，后续写入失败会整体报错
                    }
                });
            }
        }
        Files.createDirectories(dir);
    }

    /** 逐个整目录拷贝勾选技能（含 scripts/ 与资源文件） */
    private void copySkills(AgentDefinition def, Path targetSkills) throws IOException {
        if (def.skills().isEmpty()) {
            return;
        }
        if (platformSkillsDir == null) {
            throw new ApiException(400, "平台技能目录未配置（app.agent.skills-dir），无法勾选技能");
        }
        for (String skillId : def.skills()) {
            Path src = platformSkillsDir.resolve(skillId);
            if (!Files.isDirectory(src)) {
                throw new ApiException(400, "技能不存在：" + skillId);
            }
            Path dst = targetSkills.resolve(skillId);
            try (Stream<Path> walk = Files.walk(src)) {
                for (Path f : walk.filter(Files::isRegularFile).toList()) {
                    Path rel = src.relativize(f);
                    Path out = dst.resolve(rel);
                    Files.createDirectories(out.getParent());
                    Files.copy(f, out);
                }
            }
        }
    }

    /** 子 agent 规格 → frontmatter .md（与 harness 动态子 agent 磁盘格式一致） */
    private static void writeSubagents(AgentDefinition def, Path targetSubagents) throws IOException {
        for (AgentDefinition.SubagentSpec spec : def.subagents()) {
            if (!SUBAGENT_NAME.matcher(spec.name()).matches()) {
                throw new ApiException(400, "子智能体名非法（仅小写字母/数字/连字符）：" + spec.name());
            }
            String md = "---\n"
                    + "name: " + spec.name() + "\n"
                    + "description: " + oneLine(spec.description()) + "\n"
                    + "emoji: " + oneLine(spec.emoji().isBlank() ? "🤖" : spec.emoji()) + "\n"
                    + "color: " + oneLine(spec.color().isBlank() ? "blue" : spec.color()) + "\n"
                    + "---\n"
                    + spec.prompt() + "\n";
            Files.write(targetSubagents.resolve(spec.name() + ".md"),
                    md.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** frontmatter 单行值：去换行防字段逃逸 */
    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]", " ").strip();
    }
}
