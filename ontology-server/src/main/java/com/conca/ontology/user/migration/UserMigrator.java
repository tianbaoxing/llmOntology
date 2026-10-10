package com.conca.ontology.user.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.conca.ontology.shared.db.migration.JsonMigrator;
import com.conca.ontology.user.mapper.SysUserMapper;
import com.conca.ontology.user.model.UserEntry;
import com.conca.ontology.user.model.UserEntryConverter;
import com.conca.ontology.user.model.UsersFile;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * users.json → sys_user 存量迁移（P1）。导入成功后源 JSON 由 Runner 归档为 .migrated。
 */
@Component
@Order(10)
public class UserMigrator implements JsonMigrator {

    private static final Logger log = LoggerFactory.getLogger(UserMigrator.class);

    private final ObjectMapper mapper;
    private final SysUserMapper userMapper;
    private final String usersFile;

    public UserMigrator(ObjectMapper mapper, SysUserMapper userMapper,
            @Value("${app.auth.users-file:data/users.json}") String usersFile) {
        this.mapper = mapper;
        this.userMapper = userMapper;
        this.usersFile = usersFile;
    }

    @Override
    public String name() {
        return "users";
    }

    @Override
    public Path sourceFile() {
        return Path.of(usersFile).toAbsolutePath().normalize();
    }

    @Override
    public boolean targetTableEmpty() {
        return userMapper.selectCount(null) == 0;
    }

    @Override
    @Transactional
    public void importAll() throws Exception {
        UsersFile uf = mapper.readValue(Files.readString(sourceFile()), UsersFile.class);
        List<UserEntry> users = uf.users() == null ? List.of() : uf.users();
        for (UserEntry u : users) {
            userMapper.insert(UserEntryConverter.toEntity(u));
        }
        log.info("users.json 导入完成：{} 个用户（文件全局 version={} 弃用）", users.size(), uf.version());
    }
}
