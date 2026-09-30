package com.conca.ontology.wiki.service;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.conca.ontology.graph.dto.view.EntityDictEntry;
import com.conca.ontology.graph.service.GraphQueryService;
import com.conca.ontology.wiki.dto.view.WikiEntityView;

/**
 * Wiki 正文实体匹配：GraphQueryService 实体词典（概念层 + 实例层）在 MD 原文上做最长优先、
 * 非重叠、大小写不敏感的命中检测，返回该文档实际命中的实体集合（前端只包裹不匹配）。
 * 两级缓存：词典 TTL 5 分钟（节点增删最迟一个 TTL 生效）；文档结果 key = mdName + convertedAt
 * （重转自动失效），词典刷新时一并清空。
 */
@Service
public class WikiEntityService {

    private static final long DICT_TTL_MS = 5 * 60 * 1000L;
    /** 名称长度下限：单字实体误命中率高，不参与匹配 */
    private static final int MIN_NAME_LEN = 2;
    /** 片段/词典防御上限 */
    private static final int MAX_ENTITIES_PER_DOC = 200;

    private final GraphQueryService graphQueryService;

    private volatile List<EntityDictEntry> dict;
    private volatile long dictLoadedAt;
    private final Map<String, List<WikiEntityView>> docCache = new ConcurrentHashMap<>();

    public WikiEntityService(GraphQueryService graphQueryService) {
        this.graphQueryService = graphQueryService;
    }

    /** 单篇文档实体匹配（结果缓存；content 为剔除首行转换信息后的 MD 原文） */
    public List<WikiEntityView> match(String mdName, String convertedAt, String content) {
        String cacheKey = mdName + "\n" + (convertedAt == null ? "" : convertedAt);
        return docCache.computeIfAbsent(cacheKey, k -> doMatch(content));
    }

    private List<WikiEntityView> doMatch(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String lc = content.toLowerCase();
        List<EntityDictEntry> entries = dict();
        if (entries.isEmpty()) {
            return List.of();
        }
        boolean[] occupied = new boolean[lc.length()];
        List<WikiEntityView> hits = new ArrayList<>();
        for (EntityDictEntry e : entries) {   // 词典已按名称长度降序：最长优先
            String name = e.name();
            if (name == null || name.length() < MIN_NAME_LEN) {
                continue;
            }
            String nameLc = name.toLowerCase();
            boolean ascii = isAscii(name);
            long count = 0;
            int idx = 0;
            while ((idx = lc.indexOf(nameLc, idx)) >= 0) {
                int end = idx + nameLc.length();
                if (!overlaps(occupied, idx, end)
                        && (!ascii || asciiBoundaryOk(lc, idx, end))) {
                    mark(occupied, idx, end);   // 命中区间占位，短名称不再重复命中
                    count++;
                }
                idx = end;
            }
            if (count > 0) {
                hits.add(new WikiEntityView(name, e.label(), e.key(), e.layer(), count));
            }
        }
        return hits.size() > MAX_ENTITIES_PER_DOC ? List.copyOf(hits.subList(0, MAX_ENTITIES_PER_DOC)) : List.copyOf(hits);
    }

    /** 实体词典：同名实体保留 label 字典序最小者（Cypher 已 ORDER BY label，顺序稳定） */
    private List<EntityDictEntry> dict() {
        long now = System.currentTimeMillis();
        List<EntityDictEntry> d = dict;
        if (d == null || now - dictLoadedAt > DICT_TTL_MS) {
            synchronized (this) {
                if (dict == null || System.currentTimeMillis() - dictLoadedAt > DICT_TTL_MS) {
                    Map<String, EntityDictEntry> byName = new LinkedHashMap<>();
                    for (EntityDictEntry e : graphQueryService.entityDict()) {
                        if (e.name() != null && !e.name().isBlank()) {
                            byName.putIfAbsent(e.name(), e);
                        }
                    }
                    // 最长优先：名称长度降序，同长按名称字典序稳定
                    List<EntityDictEntry> list = new ArrayList<>(byName.values());
                    list.sort(Comparator.comparingInt((EntityDictEntry e) -> e.name().length()).reversed()
                            .thenComparing(EntityDictEntry::name));
                    dict = list;
                    dictLoadedAt = System.currentTimeMillis();
                    docCache.clear();   // 词典刷新，文档级结果一并失效
                }
                d = dict;
            }
        }
        return d;
    }

    /** 纯 ASCII 名称须词边界匹配：命中位置前后不能是字母/数字（如 Era 不得命中 generated 内部） */
    private static boolean asciiBoundaryOk(String lc, int start, int end) {
        boolean prevOk = start == 0 || !isAsciiAlnum(lc.charAt(start - 1));
        boolean nextOk = end == lc.length() || !isAsciiAlnum(lc.charAt(end));
        return prevOk && nextOk;
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 128) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static boolean overlaps(boolean[] occupied, int start, int end) {
        for (int i = start; i < end; i++) {
            if (occupied[i]) {
                return true;
            }
        }
        return false;
    }

    private static void mark(boolean[] occupied, int start, int end) {
        for (int i = start; i < end; i++) {
            occupied[i] = true;
        }
    }
}
