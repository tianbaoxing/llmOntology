package com.conca.ontology.model.service;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.conca.ontology.model.entity.ModelInfoEntity;
import com.conca.ontology.model.mapper.ModelInfoMapper;
import com.conca.ontology.shared.web.ApiException;

/**
 * AI 模型信息管理服务（MySQL model_info 表，源 Epoch AI 数据集，每模型一行，57 列）。
 *
 * model 为自然主键（VARCHAR(160)，utf8mb4_bin 区分大小写），编辑时主键不可变；
 * 除 last_modified（源数据维护字段，编辑不覆盖）外全量字段可空、可清空。
 *
 * 校验策略：model 必填 + 唯一；VARCHAR 列超宽直接报错（不静默截断，
 * 与导入脚本 model_info_import.sql 同等严格）；数值格式由 Jackson 类型绑定天然校验。
 *
 * @author tianbaoxing
 * @date 2026-10-29
 */
@Service
public class ModelInfoService {

    private static final Logger log = LoggerFactory.getLogger(ModelInfoService.class);

    /** confidence 合法取值（源数据集枚举） */
    private static final Set<String> CONFIDENCE_VALUES = Set.of("Confident", "Likely", "Speculative", "Unknown");

    /** VARCHAR 列宽校验（TEXT 列 64KB 上限，表单输入不可能触及，不校验） */
    private static final List<StringSpec> STRING_SPECS = List.of(
            new StringSpec("domain（领域）", 128, ModelInfoEntity::getDomain),
            new StringSpec("task（任务）", 512, ModelInfoEntity::getTask),
            new StringSpec("organization（发布组织）", 1024, ModelInfoEntity::getOrganization),
            new StringSpec("reference（参考文献）", 512, ModelInfoEntity::getReference),
            new StringSpec("link（来源链接）", 1024, ModelInfoEntity::getLink),
            new StringSpec("notability_criteria（知名度判定标准）", 128, ModelInfoEntity::getNotabilityCriteria),
            new StringSpec("training_hardware（训练硬件）", 128, ModelInfoEntity::getTrainingHardware),
            new StringSpec("approach（训练方法）", 128, ModelInfoEntity::getApproach),
            new StringSpec("confidence（数据置信度）", 16, ModelInfoEntity::getConfidence),
            new StringSpec("wikitext_and_penn_treebank_data", 255, ModelInfoEntity::getWikitextAndPennTreebankData),
            new StringSpec("model_accessibility（模型可获取方式）", 64, ModelInfoEntity::getModelAccessibility),
            new StringSpec("country_of_organization（组织所在国家）", 1024, ModelInfoEntity::getCountryOfOrganization),
            new StringSpec("base_model（基座模型）", 128, ModelInfoEntity::getBaseModel),
            new StringSpec("training_cloud_compute_vendor（云计算厂商）", 64, ModelInfoEntity::getTrainingCloudComputeVendor),
            new StringSpec("training_data_center（训练数据中心）", 255, ModelInfoEntity::getTrainingDataCenter),
            new StringSpec("archived_links（存档链接）", 512, ModelInfoEntity::getArchivedLinks),
            new StringSpec("organization_categorization（组织分类）", 512, ModelInfoEntity::getOrganizationCategorization),
            new StringSpec("training_code_accessibility（训练代码可获取性）", 32, ModelInfoEntity::getTrainingCodeAccessibility),
            new StringSpec("numerical_format（数值精度格式）", 64, ModelInfoEntity::getNumericalFormat),
            new StringSpec("training_compute_estimation_method（算力估算方法）", 128, ModelInfoEntity::getTrainingComputeEstimationMethod),
            new StringSpec("hugging_face_developer_id（HF 开发者 ID）", 64, ModelInfoEntity::getHuggingFaceDeveloperId));

    private final ModelInfoMapper mapper;

    public ModelInfoService(ModelInfoMapper mapper) {
        this.mapper = mapper;
    }

    // ---------------- 查询 ----------------

    /** 分页 + 条件查询（model/organization 模糊忽略大小写——表 collation 为 utf8mb4_bin，LIKE 须双侧 LOWER；
     *  布尔仅 true/false 参与过滤；默认按 model 升序） */
    public PageView page(int page, int pageSize, String model, String organization,
            String confidence, Boolean foundationModel, Boolean frontierModel, Boolean openWeights) {
        int p = Math.max(1, page);
        int size = Math.min(100, Math.max(1, pageSize));
        LambdaQueryWrapper<ModelInfoEntity> w = new LambdaQueryWrapper<>();
        if (model != null && !model.isBlank()) {
            w.apply("LOWER(model) LIKE LOWER({0})", "%" + model.trim() + "%");
        }
        if (organization != null && !organization.isBlank()) {
            w.apply("LOWER(organization) LIKE LOWER({0})", "%" + organization.trim() + "%");
        }
        if (confidence != null && !confidence.isBlank()) {
            w.eq(ModelInfoEntity::getConfidence, confidence.trim());
        }
        if (foundationModel != null) {
            w.eq(ModelInfoEntity::getFoundationModel, foundationModel);
        }
        if (frontierModel != null) {
            w.eq(ModelInfoEntity::getFrontierModel, frontierModel);
        }
        if (openWeights != null) {
            w.eq(ModelInfoEntity::getOpenModelWeights, openWeights);
        }
        w.orderByAsc(ModelInfoEntity::getModel);
        Page<ModelInfoEntity> r = mapper.selectPage(Page.of(p, size), w);
        return new PageView(r.getRecords(), r.getTotal(), (int) r.getCurrent(), (int) r.getSize());
    }

    /** 单条详情（不存在抛 404） */
    public ModelInfoEntity detail(String model) {
        return requireExisting(model);
    }

    // ---------------- 写入 ----------------

    /** 新增（model 查重，重复抛 409） */
    public ModelInfoEntity create(ModelInfoEntity e) {
        validate(e);
        if (mapper.selectById(e.getModel()) != null) {
            throw new ApiException(409, "模型已存在：" + e.getModel());
        }
        mapper.insert(e);
        log.info("model_info created: {}", e.getModel());
        return mapper.selectById(e.getModel());
    }

    /** 编辑（以 model 定位；除 lastModified 外全量覆盖，未携带字段写 NULL） */
    public ModelInfoEntity update(ModelInfoEntity e) {
        validate(e);
        requireExisting(e.getModel());
        mapper.updateById(e);
        log.info("model_info updated: {}", e.getModel());
        return mapper.selectById(e.getModel());
    }

    /** 删除（物理删除，不可恢复） */
    public void delete(String model) {
        requireExisting(model);
        mapper.deleteById(model);
        log.info("model_info deleted: {}", model);
    }

    // ---------------- 内部 ----------------

    /** 按 model 取已有行，不存在抛 404 */
    private ModelInfoEntity requireExisting(String model) {
        if (model == null || model.isBlank()) {
            throw new ApiException(400, "model（模型名称）不能为空");
        }
        ModelInfoEntity e = mapper.selectById(model);
        if (e == null) {
            throw new ApiException(404, "模型不存在：" + model);
        }
        return e;
    }

    /** 写入校验：model 必填/≤160、confidence 枚举、VARCHAR 列宽 */
    private void validate(ModelInfoEntity e) {
        if (e.getModel() == null || e.getModel().isBlank()) {
            throw new ApiException(400, "model（模型名称）不能为空");
        }
        e.setModel(e.getModel().trim());
        if (e.getModel().length() > 160) {
            throw new ApiException(400, "model（模型名称）超长：最多 160 字");
        }
        if (e.getConfidence() != null && !CONFIDENCE_VALUES.contains(e.getConfidence())) {
            throw new ApiException(400, "confidence（数据置信度）取值须为："
                    + String.join("/", CONFIDENCE_VALUES));
        }
        for (StringSpec spec : STRING_SPECS) {
            String v = spec.getter().apply(e);
            if (v != null && v.length() > spec.max()) {
                throw new ApiException(400, spec.label() + "超长：最多 " + spec.max() + " 字");
            }
        }
    }

    /** VARCHAR 列宽规格 */
    private record StringSpec(String label, int max, Function<ModelInfoEntity, String> getter) {
    }

    /** 分页响应视图（rows 7 个大数列为字符串，见实体序列化注解） */
    public record PageView(List<ModelInfoEntity> rows, long total, int page, int pageSize) {
    }
}
