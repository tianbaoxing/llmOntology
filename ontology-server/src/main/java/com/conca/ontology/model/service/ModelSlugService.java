package com.conca.ontology.model.service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.conca.ontology.model.dto.ModelOptionDTO;
import com.conca.ontology.model.dto.ModelOptionPageDTO;
import com.conca.ontology.model.entity.ModelInfoEntity;
import com.conca.ontology.model.mapper.ModelInfoMapper;

/**
 * OpenRouter 模型 slug 服务：chat 页底部「模型切换」的后端支撑。
 *
 * model_info 表存的是展示名（如 DeepSeek V4 Flash 0731 / Z.ai (Zhipu AI)），
 * OpenRouter 接口要的是小写 slug（如 deepseek/deepseek-v4-flash-0731 / z-ai/glm-5.3-flash），
 * 两者不可逆，本服务承担正向转换：
 *
 * - 规则推导：模型段小写 + 空白转连字符 + 保留点号（GLM-5.3-Flash → glm-5.3-flash）；
 *   组织段取主名（首个括号/逗号/分号前，Z.ai (Zhipu AI) → Z.ai），非字母数字（含点号）
 *   一律转连字符（→ z-ai）；已知组织段别名修正（Meta→meta-llama 等）。
 * - 合法性索引：isRoutable(slug) 校验请求携带的 slug 是否来自 model_info 推导
 *   （服务端唯一闸口，防任意字符串透传 OpenRouter）；索引 miss 时带 10s 冷却
 *   重扫全表两列（新录入模型即时生效）。
 *
 * @author tianbaoxing
 * @date 2026-10-30
 */
@Service
public class ModelSlugService {

    private static final Logger log = LoggerFactory.getLogger(ModelSlugService.class);

    /** 组织 slug 别名修正表：规则推导结果 → OpenRouter 实际组织段（小写键） */
    private static final Map<String, String> ORG_SLUG_ALIASES = Map.of(
            "meta", "meta-llama",
            "mistral-ai", "mistralai",
            "alibaba", "qwen",
            "google-deepmind", "google",
            "moonshot-ai", "moonshotai",
            "ai2", "allenai");

    /** isRoutable 索引 miss 触发重扫的冷却间隔（防连续失败时每请求全表扫） */
    private static final long INDEX_RELOAD_COOLDOWN_MS = 10_000L;

    private final ModelInfoService modelInfoService;
    private final ModelInfoMapper modelInfoMapper;

    /** 合法 slug 全集（model_info 推导）；isRoutable 校验用 */
    private volatile Set<String> validSlugs = Set.of();
    private volatile long indexLoadedAt = 0;

    public ModelSlugService(ModelInfoService modelInfoService, ModelInfoMapper modelInfoMapper) {
        this.modelInfoService = modelInfoService;
        this.modelInfoMapper = modelInfoMapper;
    }

    // ---------------- slug 推导 ----------------

    /** 展示名 → OpenRouter slug（规则推导 + 组织别名修正）；模型名空返回空串 */
    public String resolve(String model, String organization) {
        String mSlug = modelSlug(model);
        if (mSlug.isEmpty()) {
            return "";
        }
        String org = orgSlug(organization);
        String fixed = ORG_SLUG_ALIASES.getOrDefault(org, org);
        return fixed.isEmpty() ? mSlug : fixed + "/" + mSlug;
    }

    /** 模型段 slug：小写、空白转连字符、保留点号（GLM-5.3-Flash → glm-5.3-flash） */
    static String modelSlug(String model) {
        return slugify(model, true);
    }

    /** 组织段 slug：主名 + 非字母数字（含点号）转连字符（Z.ai (Zhipu AI) → z-ai） */
    static String orgSlug(String organization) {
        return slugify(primaryName(organization), false);
    }

    /** 取组织主名：首个括号/逗号/分号前的部分（Z.ai (Zhipu AI) → Z.ai） */
    static String primaryName(String organization) {
        if (organization == null || organization.isBlank()) {
            return "";
        }
        String s = organization.strip();
        int cut = s.length();
        for (char c : new char[]{'(', '（', ',', '，', ';', '；'}) {
            int i = s.indexOf(c);
            if (i >= 0 && i < cut) {
                cut = i;
            }
        }
        return s.substring(0, cut).strip();
    }

    /**
     * slug 化：小写；字母数字保留，点号按需保留；其余字符（含空白、连字符、点号外的标点）
     * 折叠为单个连字符；去首尾连字符。
     */
    private static String slugify(String s, boolean keepDot) {
        if (s == null || s.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean prevDash = false;
        for (char c : s.strip().toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c) || (keepDot && c == '.')) {
                sb.append(c);
                prevDash = false;
            } else if (!prevDash && sb.length() > 0) {
                sb.append('-');
                prevDash = true;
            }
        }
        String r = sb.toString();
        return r.endsWith("-") ? r.substring(0, r.length() - 1) : r;
    }

    // ---------------- 合法性校验（chat 提交闸口） ----------------

    /**
     * chat 请求携带的 slug 是否合法（来自 model_info 推导）。
     * 索引 miss 且冷却期已过时重扫全表（只取 model/organization 两列），新录入模型即时生效。
     */
    public boolean isRoutable(String slug) {
        if (slug == null || slug.isBlank()) {
            return false;
        }
        String key = slug.strip();
        if (validSlugs.contains(key)) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - indexLoadedAt >= INDEX_RELOAD_COOLDOWN_MS) {
            synchronized (this) {
                if (now - indexLoadedAt >= INDEX_RELOAD_COOLDOWN_MS) {
                    rebuildIndex();
                    return validSlugs.contains(key);
                }
            }
        }
        return false;
    }

    /** 全表两列扫描重建合法性索引 */
    private void rebuildIndex() {
        try {
            Set<String> idx = new HashSet<>();
            List<ModelInfoEntity> rows = modelInfoMapper.selectList(new LambdaQueryWrapper<ModelInfoEntity>()
                    .select(ModelInfoEntity::getModel, ModelInfoEntity::getOrganization));
            for (ModelInfoEntity e : rows) {
                String slug = resolve(e.getModel(), e.getOrganization());
                if (!slug.isBlank()) {
                    idx.add(slug);
                }
            }
            validSlugs = Set.copyOf(idx);
            log.info("模型 slug 索引已重建：{} 个", idx.size());
        } catch (Exception e) {
            log.warn("模型 slug 索引重建失败：{}", e.getMessage());
        } finally {
            indexLoadedAt = System.currentTimeMillis();
        }
    }

    // ---------------- 切换选项（chat 页弹框数据源） ----------------

    /** 分页查询切换选项：model_info 精简行 + 推导出的 OpenRouter slug */
    public ModelOptionPageDTO options(int page, int pageSize, String model, String organization,
            String confidence, Boolean foundationModel, Boolean frontierModel, Boolean openWeights) {
        ModelInfoService.PageView pv = modelInfoService.page(page, pageSize, model, organization,
                confidence, foundationModel, frontierModel, openWeights);
        List<ModelOptionDTO> rows = pv.rows().stream().map(this::toOption).toList();
        return new ModelOptionPageDTO(rows, pv.total(), pv.page(), pv.pageSize());
    }

    /** 实体 → 精简选项行（大数列转字符串规避 JS 精度丢失；全字段可空） */
    private ModelOptionDTO toOption(ModelInfoEntity e) {
        return new ModelOptionDTO(
                e.getModel(),
                e.getOrganization(),
                resolve(e.getModel(), e.getOrganization()),
                e.getPublicationDate() == null ? null : e.getPublicationDate().toString(),
                e.getDomain(),
                e.getTask(),
                e.getConfidence(),
                plain(e.getParameters()),
                plain(e.getTrainingComputeFlop()),
                e.getFoundationModel(),
                e.getFrontierModel(),
                e.getOpenModelWeights(),
                e.getBaseModel());
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }
}
