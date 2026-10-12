package com.conca.ontology.user.migration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.conca.ontology.shared.db.migration.JsonMigrator;
import com.conca.ontology.user.entity.UserPromptEntity;
import com.conca.ontology.user.mapper.UserPromptMapper;
import com.conca.ontology.user.model.UserPromptEntry;
import com.conca.ontology.user.model.UserPromptEntryConverter;
import com.conca.ontology.user.model.UserPromptFile;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * data/prompts/{userId}.json（按用户一文件）→ user_prompt 多行表存量迁移（P5）。
 * 每条提示词一行；顶层 version 不再落库（仅在旧文件写盘时回填、从未被读取）。
 * 源为目录（首个目录型迁移源）：importAll 扫描 *.json，userId 取文件名，
 * 导入成功后整个目录由 Runner 归档为 prompts.migrated。
 * 全新部署无默认数据：无目录即无提示词，对齐原「文件不存在视为空列表」（initDefaults 空实现）。
 */
@Component
@Order(60)
public class UserPromptMigrator implements JsonMigrator {

    private static final Logger log = LoggerFactory.getLogger(UserPromptMigrator.class);

    /** 与 UserPromptService.USER_ID_RE 同源：userId 与注册用户名同源字符集 */
    private static final Pattern USER_ID_RE = Pattern.compile("^[A-Za-z0-9_\\u4e00-\\u9fa5]{2,32}$");

    private final ObjectMapper mapper;
    private final UserPromptMapper promptMapper;
    private final String promptsDir;

    public UserPromptMigrator(ObjectMapper mapper, UserPromptMapper promptMapper,
            @Value("${app.prompts-dir:data/prompts}") String promptsDir) {
        this.mapper = mapper;
        this.promptMapper = promptMapper;
        this.promptsDir = promptsDir;
    }

    @Override
    public String name() {
        return "user-prompt";
    }

    /** 源为目录（按用户一文件）；Runner 以存在性判断，归档时整体 rename */
    @Override
    public Path sourceFile() {
        return Path.of(promptsDir).toAbsolutePath().normalize();
    }

    @Override
    public boolean targetTableEmpty() {
        return promptMapper.selectCount(null) == 0;
    }

    @Override
    @Transactional
    public void importAll() throws Exception {
        List<Path> files;
        try (Stream<Path> s = Files.list(sourceFile())) {
            files = s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
        int count = 0;
        for (Path f : files) {
            String userId = fileNameWithoutExt(f);
            if (!USER_ID_RE.matcher(userId).matches()) {
                throw new IllegalStateException("提示词文件名不是合法用户标识: " + f.getFileName());
            }
            UserPromptFile pf = mapper.readValue(Files.readString(f, StandardCharsets.UTF_8), UserPromptFile.class);
            List<UserPromptEntry> items = pf.items() == null ? List.of() : pf.items();
            for (UserPromptEntry e : items) {
                UserPromptEntity row = UserPromptEntryConverter.toEntity(e);
                row.setUserId(userId);
                promptMapper.insert(row);
                count++;
            }
        }
        log.info("data/prompts 导入完成：{} 个用户文件、{} 条提示词（文件全局 version 弃用）", files.size(), count);
    }

    private static String fileNameWithoutExt(Path p) {
        String n = p.getFileName().toString();
        return n.substring(0, n.length() - ".json".length());
    }
}
