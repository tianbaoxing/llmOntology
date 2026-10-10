package com.conca.ontology.graph.service;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.conca.ontology.graph.dto.request.ProfileSaveRequest;
import com.conca.ontology.graph.dto.result.ProfileSaveResult;
import com.conca.ontology.graph.dto.view.ProfileView;
import com.conca.ontology.graph.entity.OntologyProfileItem;
import com.conca.ontology.graph.mapper.OntologyProfileItemMapper;
import com.conca.ontology.graph.model.ProfileEntry;

/**
 * 构建期问询记录（用户画像 / ontology 画像）：MySQL ontology_profile 多行表为唯一事实源
 * （P2 起由 ontology-profile.json 迁移而来，每条问答一行，无全局 version）。
 * 按 key upsert（同 key 覆盖且保留原位置，新 key 追加在尾部）；updatedAt 存 ISO-8601 原文保留纳秒精度。
 */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final OntologyProfileItemMapper itemMapper;

    public ProfileService(OntologyProfileItemMapper itemMapper) {
        this.itemMapper = itemMapper;
    }

    /** 全量读取（无行返回空列表，供 LLM 回看构建上下文；按首次创建顺序） */
    public ProfileView load() {
        List<ProfileEntry> items = itemMapper
                .selectList(new LambdaQueryWrapper<OntologyProfileItem>()
                        .orderByAsc(OntologyProfileItem::getId))
                .stream().map(ProfileService::toEntry).toList();
        return new ProfileView(items);
    }

    /** 批量 upsert：按 key 覆盖已有条目（原位置），新 key 追加，事务原子 */
    @Transactional
    public ProfileSaveResult save(ProfileSaveRequest req) {
        String now = LocalDateTime.now().toString();
        for (ProfileSaveRequest.Item item : req.items()) {
            String phase = item.phase() == null || item.phase().isBlank() ? "intake" : item.phase();
            OntologyProfileItem row = itemMapper.selectOne(new LambdaQueryWrapper<OntologyProfileItem>()
                    .eq(OntologyProfileItem::getItemKey, item.key()));
            if (row == null) {
                row = new OntologyProfileItem();
                row.setItemKey(item.key());
                fill(row, phase, item, now);
                itemMapper.insert(row);
            } else {
                fill(row, phase, item, now);
                itemMapper.updateById(row);
            }
        }
        int count = Math.toIntExact(itemMapper.selectCount(null));
        log.info("profile saved: {} entries", count);
        return new ProfileSaveResult(count);
    }

    private static void fill(OntologyProfileItem row, String phase, ProfileSaveRequest.Item item, String now) {
        row.setPhase(phase);
        row.setQuestion(item.question());
        row.setAnswer(item.answer());
        row.setUpdatedAt(now);
    }

    private static ProfileEntry toEntry(OntologyProfileItem r) {
        return new ProfileEntry(r.getPhase(), r.getItemKey(), r.getQuestion(), r.getAnswer(), r.getUpdatedAt());
    }
}
