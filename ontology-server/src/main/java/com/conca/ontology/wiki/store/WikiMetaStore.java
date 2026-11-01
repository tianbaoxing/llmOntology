package com.conca.ontology.wiki.store;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.conca.ontology.wiki.entity.WikiMetadata;
import com.conca.ontology.wiki.mapper.WikiMetadataMapper;

/**
 * Wiki 手工元数据存储（MySQL wiki_metadata，P10 起，由 data/wiki-metadata.json 单文件迁移而来）：
 *   每 MD 一行，无行 = 无手工元数据（对应原文件不存在语义，无默认值）。
 *   实体（WikiMetadata）直通 service；updated_at 仅由本类写入时设置，业务侧不感知。
 *   并发由 WikiService 的 synchronized 整体串行保护（读-改-写语义不变）。
 *
 * @author tianbaoxing
 * @date 2026-10-24
 */
@Component
public class WikiMetaStore {

    private final WikiMetadataMapper mapper;

    public WikiMetaStore(WikiMetadataMapper mapper) {
        this.mapper = mapper;
    }

    /** 全量按 mdName 索引（文档列表顺序由文件列表决定，此处顺序无语义） */
    public Map<String, WikiMetadata> byMdName() {
        Map<String, WikiMetadata> byName = new LinkedHashMap<>();
        for (WikiMetadata m : mapper.selectList(null)) {
            byName.put(m.getMdName(), m);
        }
        return byName;
    }

    /** 单条查询（主键点查），无行返回 null */
    public WikiMetadata get(String mdName) {
        return mapper.selectById(mdName);
    }

    /** 行级 upsert（同 mdName 覆盖；updated_at 恒由本方法刷为当前时刻，调用方设置无效） */
    public void upsert(WikiMetadata m) {
        m.setUpdatedAt(LocalDateTime.now());
        if (mapper.selectById(m.getMdName()) == null) {
            mapper.insert(m);
        } else {
            mapper.updateById(m);
        }
    }

    /** 孤儿清理：剔除 mdName 不在 valid 集合内的行（valid 空 = 全删），返回删除行数 */
    public int deleteNotIn(Set<String> valid) {
        if (valid.isEmpty()) {
            return mapper.delete(null);
        }
        return mapper.delete(new LambdaQueryWrapper<WikiMetadata>()
                .notIn(WikiMetadata::getMdName, valid));
    }
}
