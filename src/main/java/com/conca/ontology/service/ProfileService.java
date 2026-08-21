package com.conca.ontology.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.conca.ontology.dto.ProfileSaveRequest;
import com.conca.ontology.dto.ProfileSaveResult;
import com.conca.ontology.dto.ProfileView;
import com.conca.ontology.model.ProfileEntry;
import com.conca.ontology.model.ProfileFile;

/**
 * 构建期问询记录（用户画像 / ontology 画像）：ontology-profile.json 为唯一事实源。
 * 按 key upsert（同 key 重复问答覆盖，保留最新画像）；每次变更 version + 1 并原子落盘。
 */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final ObjectMapper mapper;

    @Value("${graph.profile-file:data/ontology-profile.json}")
    private String profileFile;

    public ProfileService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 全量读取（文件不存在返回空结构，供 LLM 回看构建上下文） */
    public ProfileView load() {
        Path file = profilePath();
        if (Files.notExists(file)) {
            return new ProfileView(0, List.of());
        }
        ProfileFile pf = read(file);
        return new ProfileView(pf.version(), pf.items());
    }

    /** 批量 upsert：按 key 覆盖已有条目，原子落盘 */
    public synchronized ProfileSaveResult save(ProfileSaveRequest req) {
        Path file = profilePath();
        ProfileFile pf = Files.exists(file) ? read(file) : new ProfileFile(0, List.of());
        Map<String, ProfileEntry> byKey = new LinkedHashMap<>();
        for (ProfileEntry e : pf.items()) {
            byKey.put(e.key(), e);
        }
        String now = LocalDateTime.now().toString();
        for (ProfileSaveRequest.Item item : req.items()) {
            String phase = item.phase() == null || item.phase().isBlank() ? "intake" : item.phase();
            byKey.put(item.key(), new ProfileEntry(phase, item.key(), item.question(), item.answer(), now));
        }
        List<ProfileEntry> items = new ArrayList<>(byKey.values());
        ProfileFile next = new ProfileFile(pf.version() + 1, items);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), next);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw new IllegalStateException("failed to save profile file: " + file.toAbsolutePath(), e);
        }
        log.info("profile saved: {} entries (version {})", items.size(), next.version());
        return new ProfileSaveResult(next.version(), items.size());
    }

    private ProfileFile read(Path file) {
        try {
            return mapper.readValue(file.toFile(), ProfileFile.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load profile file: " + file.toAbsolutePath(), e);
        }
    }

    private Path profilePath() {
        return Path.of(profileFile).toAbsolutePath();
    }
}
