package com.conca.ontology.model.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * AI 模型信息行（model_info，源 Epoch AI all_ai_models.csv，每模型一行，57 列）。
 *
 * model 为自然主键（VARCHAR(160)，utf8mb4_bin），无自增 id，编辑时主键不可变；
 * 编辑走 updateById 全量覆盖语义：除 lastModified 外的所有字段标注
 * updateStrategy = ALWAYS（请求体未携带即写 NULL，支持表单清空字段）；
 * lastModified 为源数据维护字段（不参与覆盖，请求体不传则保持原值）。
 *
 * 7 个 DECIMAL(45,10) 大数列（上限 1.13e+27，超出 JS Number 2^53 安全范围）
 * 序列化为字符串输出（ToStringSerializer），前端原样回显与回传，规避精度丢失。
 *
 * @author tianbaoxing
 * @date 2026-10-29
 */
@TableName("model_info")
public class ModelInfoEntity {

    /** 模型名称，自然主键（原列 Model） */
    @TableId(value = "model", type = IdType.INPUT)
    private String model;

    /** 领域，逗号分隔多值（原列 Domain） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String domain;

    /** 任务，逗号分隔多值（原列 Task） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String task;

    /** 发布组织，多值（原列 Organization） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String organization;

    /** 作者列表（原列 Authors） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String authors;

    /** 发布日期（原列 Publication date） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate publicationDate;

    /** 参考文献/来源标题（原列 Reference） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String reference;

    /** 来源链接（原列 Link） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String link;

    /** 引用数（原列 Citations） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal citations;

    /** 知名度判定标准（原列 Notability criteria） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String notabilityCriteria;

    /** 知名度判定标准备注（原列 Notability criteria notes） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String notabilityCriteriaNotes;

    /** 参数量（原列 Parameters），大数列：序列化为字符串 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal parameters;

    /** 参数量备注（原列 Parameters notes） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String parametersNotes;

    /** 训练算力，FLOP（原列 Training compute (FLOP)），大数列：序列化为字符串 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal trainingComputeFlop;

    /** 训练算力备注（原列 Training compute notes） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String trainingComputeNotes;

    /** 训练数据集总规模，token 数（原列 Training dataset size (total)），大数列：序列化为字符串 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal trainingDatasetSizeTotal;

    /** 数据集规模备注（原列 Dataset size notes） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String datasetSizeNotes;

    /** 训练时长，小时（原列 Training time (hours)） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal trainingTimeHours;

    /** 训练时长备注（原列 Training time notes） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String trainingTimeNotes;

    /** 训练硬件（原列 Training hardware） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String trainingHardware;

    /** 训练方法，逗号分隔多值（原列 Approach） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String approach;

    /** 数据置信度：Confident/Likely/Speculative/Unknown（原列 Confidence） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String confidence;

    /** 摘要，可含多行文本（原列 Abstract；abstract 为 Java 关键字，字段名 abstractText） */
    @TableField(value = "abstract", updateStrategy = FieldStrategy.ALWAYS)
    private String abstractText;

    /** 训练轮数（原列 Epochs） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal epochs;

    /** WikiText 与 Penn Treebank 数据标注（原列 WikiText and Penn Treebank data） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String wikitextAndPennTreebankData;

    /** 模型可获取方式（原列 Model accessibility） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String modelAccessibility;

    /** 组织所在国家，多值（原列 Country (of organization)） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String countryOfOrganization;

    /** 基座模型（原列 Base model） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String baseModel;

    /** 微调算力，FLOP（原列 Finetune compute (FLOP)），大数列：序列化为字符串 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal finetuneComputeFlop;

    /** 微调算力备注（原列 Finetune compute notes） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String finetuneComputeNotes;

    /** 训练硬件数量（原列 Hardware quantity） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal hardwareQuantity;

    /** 硬件利用率，MFU（原列 Hardware utilization (MFU)） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal hardwareUtilizationMfu;

    /** 数据最后修改时间，UTC（原列 Last modified，源值带 +00:00 偏移，入库截断）；源数据维护字段，编辑时不覆盖 */
    private LocalDateTime lastModified;

    /** 训练云计算厂商（原列 Training cloud compute vendor） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String trainingCloudComputeVendor;

    /** 训练数据中心（原列 Training data center） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String trainingDataCenter;

    /** 存档链接（原列 Archived links） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String archivedLinks;

    /** 训练批大小（原列 Batch size） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal batchSize;

    /** 批大小备注（原列 Batch size notes） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String batchSizeNotes;

    /** 组织分类，逗号分隔多值（原列 Organization categorization） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String organizationCategorization;

    /** 是否基础模型：true=是 false=否（原列 Foundation model，源值 True） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Boolean foundationModel;

    /** 训练算力下界，FLOP（原列 Training compute lower bound），大数列：序列化为字符串 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal trainingComputeLowerBound;

    /** 训练算力上界，FLOP（原列 Training compute upper bound），大数列：序列化为字符串 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal trainingComputeUpperBound;

    /** 训练芯片小时数（原列 Training chip-hours） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal trainingChipHours;

    /** 训练代码可获取性（原列 Training code accessibility） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String trainingCodeAccessibility;

    /** 可获取性备注（原列 Accessibility notes） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String accessibilityNotes;

    /** 算力是否可能超 1e23 FLOP：true=是 false=否（原列 Possibly over 1e23 FLOP，源值 True）；
     *  列名 possibly_over_1e23_flop 与属性名驼峰自动映射结果不一致，须显式指定列名 */
    @TableField(value = "possibly_over_1e23_flop", updateStrategy = FieldStrategy.ALWAYS)
    private Boolean possiblyOver1e23Flop;

    /** 训练算力成本，2023 年美元（原列 Training compute cost (2023 USD)）；
     *  列名 training_compute_cost_2023_usd 与属性名驼峰自动映射结果不一致，须显式指定列名 */
    @TableField(value = "training_compute_cost_2023_usd", updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal trainingComputeCost2023Usd;

    /** 利用率备注（原列 Utilization notes） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String utilizationNotes;

    /** 数值精度格式，如 BF16/FP8（原列 Numerical format） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String numericalFormat;

    /** 是否前沿模型：true=是 false=否（原列 Frontier model，源值 True） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Boolean frontierModel;

    /** 训练功耗，瓦（原列 Training power draw (W)） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal trainingPowerDrawW;

    /** 训练算力估算方法（原列 Training compute estimation method） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String trainingComputeEstimationMethod;

    /** Hugging Face 开发者 ID（原列 Hugging Face developer id） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String huggingFaceDeveloperId;

    /** 后训练算力，FLOP（原列 Post-training compute (FLOP)），大数列：序列化为字符串 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal postTrainingComputeFlop;

    /** 后训练算力备注（原列 Post-training compute notes） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String postTrainingComputeNotes;

    /** 硬件利用率，HFU（原列 Hardware utilization (HFU)） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal hardwareUtilizationHfu;

    /** 是否开放模型权重：true=是 false=否（原列 Open model weights?，源值 Yes/No） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Boolean openModelWeights;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getTask() {
        return task;
    }

    public void setTask(String task) {
        this.task = task;
    }

    public String getOrganization() {
        return organization;
    }

    public void setOrganization(String organization) {
        this.organization = organization;
    }

    public String getAuthors() {
        return authors;
    }

    public void setAuthors(String authors) {
        this.authors = authors;
    }

    public LocalDate getPublicationDate() {
        return publicationDate;
    }

    public void setPublicationDate(LocalDate publicationDate) {
        this.publicationDate = publicationDate;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public BigDecimal getCitations() {
        return citations;
    }

    public void setCitations(BigDecimal citations) {
        this.citations = citations;
    }

    public String getNotabilityCriteria() {
        return notabilityCriteria;
    }

    public void setNotabilityCriteria(String notabilityCriteria) {
        this.notabilityCriteria = notabilityCriteria;
    }

    public String getNotabilityCriteriaNotes() {
        return notabilityCriteriaNotes;
    }

    public void setNotabilityCriteriaNotes(String notabilityCriteriaNotes) {
        this.notabilityCriteriaNotes = notabilityCriteriaNotes;
    }

    public BigDecimal getParameters() {
        return parameters;
    }

    public void setParameters(BigDecimal parameters) {
        this.parameters = parameters;
    }

    public String getParametersNotes() {
        return parametersNotes;
    }

    public void setParametersNotes(String parametersNotes) {
        this.parametersNotes = parametersNotes;
    }

    public BigDecimal getTrainingComputeFlop() {
        return trainingComputeFlop;
    }

    public void setTrainingComputeFlop(BigDecimal trainingComputeFlop) {
        this.trainingComputeFlop = trainingComputeFlop;
    }

    public String getTrainingComputeNotes() {
        return trainingComputeNotes;
    }

    public void setTrainingComputeNotes(String trainingComputeNotes) {
        this.trainingComputeNotes = trainingComputeNotes;
    }

    public BigDecimal getTrainingDatasetSizeTotal() {
        return trainingDatasetSizeTotal;
    }

    public void setTrainingDatasetSizeTotal(BigDecimal trainingDatasetSizeTotal) {
        this.trainingDatasetSizeTotal = trainingDatasetSizeTotal;
    }

    public String getDatasetSizeNotes() {
        return datasetSizeNotes;
    }

    public void setDatasetSizeNotes(String datasetSizeNotes) {
        this.datasetSizeNotes = datasetSizeNotes;
    }

    public BigDecimal getTrainingTimeHours() {
        return trainingTimeHours;
    }

    public void setTrainingTimeHours(BigDecimal trainingTimeHours) {
        this.trainingTimeHours = trainingTimeHours;
    }

    public String getTrainingTimeNotes() {
        return trainingTimeNotes;
    }

    public void setTrainingTimeNotes(String trainingTimeNotes) {
        this.trainingTimeNotes = trainingTimeNotes;
    }

    public String getTrainingHardware() {
        return trainingHardware;
    }

    public void setTrainingHardware(String trainingHardware) {
        this.trainingHardware = trainingHardware;
    }

    public String getApproach() {
        return approach;
    }

    public void setApproach(String approach) {
        this.approach = approach;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public String getAbstractText() {
        return abstractText;
    }

    public void setAbstractText(String abstractText) {
        this.abstractText = abstractText;
    }

    public BigDecimal getEpochs() {
        return epochs;
    }

    public void setEpochs(BigDecimal epochs) {
        this.epochs = epochs;
    }

    public String getWikitextAndPennTreebankData() {
        return wikitextAndPennTreebankData;
    }

    public void setWikitextAndPennTreebankData(String wikitextAndPennTreebankData) {
        this.wikitextAndPennTreebankData = wikitextAndPennTreebankData;
    }

    public String getModelAccessibility() {
        return modelAccessibility;
    }

    public void setModelAccessibility(String modelAccessibility) {
        this.modelAccessibility = modelAccessibility;
    }

    public String getCountryOfOrganization() {
        return countryOfOrganization;
    }

    public void setCountryOfOrganization(String countryOfOrganization) {
        this.countryOfOrganization = countryOfOrganization;
    }

    public String getBaseModel() {
        return baseModel;
    }

    public void setBaseModel(String baseModel) {
        this.baseModel = baseModel;
    }

    public BigDecimal getFinetuneComputeFlop() {
        return finetuneComputeFlop;
    }

    public void setFinetuneComputeFlop(BigDecimal finetuneComputeFlop) {
        this.finetuneComputeFlop = finetuneComputeFlop;
    }

    public String getFinetuneComputeNotes() {
        return finetuneComputeNotes;
    }

    public void setFinetuneComputeNotes(String finetuneComputeNotes) {
        this.finetuneComputeNotes = finetuneComputeNotes;
    }

    public BigDecimal getHardwareQuantity() {
        return hardwareQuantity;
    }

    public void setHardwareQuantity(BigDecimal hardwareQuantity) {
        this.hardwareQuantity = hardwareQuantity;
    }

    public BigDecimal getHardwareUtilizationMfu() {
        return hardwareUtilizationMfu;
    }

    public void setHardwareUtilizationMfu(BigDecimal hardwareUtilizationMfu) {
        this.hardwareUtilizationMfu = hardwareUtilizationMfu;
    }

    public LocalDateTime getLastModified() {
        return lastModified;
    }

    public void setLastModified(LocalDateTime lastModified) {
        this.lastModified = lastModified;
    }

    public String getTrainingCloudComputeVendor() {
        return trainingCloudComputeVendor;
    }

    public void setTrainingCloudComputeVendor(String trainingCloudComputeVendor) {
        this.trainingCloudComputeVendor = trainingCloudComputeVendor;
    }

    public String getTrainingDataCenter() {
        return trainingDataCenter;
    }

    public void setTrainingDataCenter(String trainingDataCenter) {
        this.trainingDataCenter = trainingDataCenter;
    }

    public String getArchivedLinks() {
        return archivedLinks;
    }

    public void setArchivedLinks(String archivedLinks) {
        this.archivedLinks = archivedLinks;
    }

    public BigDecimal getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(BigDecimal batchSize) {
        this.batchSize = batchSize;
    }

    public String getBatchSizeNotes() {
        return batchSizeNotes;
    }

    public void setBatchSizeNotes(String batchSizeNotes) {
        this.batchSizeNotes = batchSizeNotes;
    }

    public String getOrganizationCategorization() {
        return organizationCategorization;
    }

    public void setOrganizationCategorization(String organizationCategorization) {
        this.organizationCategorization = organizationCategorization;
    }

    public Boolean getFoundationModel() {
        return foundationModel;
    }

    public void setFoundationModel(Boolean foundationModel) {
        this.foundationModel = foundationModel;
    }

    public BigDecimal getTrainingComputeLowerBound() {
        return trainingComputeLowerBound;
    }

    public void setTrainingComputeLowerBound(BigDecimal trainingComputeLowerBound) {
        this.trainingComputeLowerBound = trainingComputeLowerBound;
    }

    public BigDecimal getTrainingComputeUpperBound() {
        return trainingComputeUpperBound;
    }

    public void setTrainingComputeUpperBound(BigDecimal trainingComputeUpperBound) {
        this.trainingComputeUpperBound = trainingComputeUpperBound;
    }

    public BigDecimal getTrainingChipHours() {
        return trainingChipHours;
    }

    public void setTrainingChipHours(BigDecimal trainingChipHours) {
        this.trainingChipHours = trainingChipHours;
    }

    public String getTrainingCodeAccessibility() {
        return trainingCodeAccessibility;
    }

    public void setTrainingCodeAccessibility(String trainingCodeAccessibility) {
        this.trainingCodeAccessibility = trainingCodeAccessibility;
    }

    public String getAccessibilityNotes() {
        return accessibilityNotes;
    }

    public void setAccessibilityNotes(String accessibilityNotes) {
        this.accessibilityNotes = accessibilityNotes;
    }

    public Boolean getPossiblyOver1e23Flop() {
        return possiblyOver1e23Flop;
    }

    public void setPossiblyOver1e23Flop(Boolean possiblyOver1e23Flop) {
        this.possiblyOver1e23Flop = possiblyOver1e23Flop;
    }

    public BigDecimal getTrainingComputeCost2023Usd() {
        return trainingComputeCost2023Usd;
    }

    public void setTrainingComputeCost2023Usd(BigDecimal trainingComputeCost2023Usd) {
        this.trainingComputeCost2023Usd = trainingComputeCost2023Usd;
    }

    public String getUtilizationNotes() {
        return utilizationNotes;
    }

    public void setUtilizationNotes(String utilizationNotes) {
        this.utilizationNotes = utilizationNotes;
    }

    public String getNumericalFormat() {
        return numericalFormat;
    }

    public void setNumericalFormat(String numericalFormat) {
        this.numericalFormat = numericalFormat;
    }

    public Boolean getFrontierModel() {
        return frontierModel;
    }

    public void setFrontierModel(Boolean frontierModel) {
        this.frontierModel = frontierModel;
    }

    public BigDecimal getTrainingPowerDrawW() {
        return trainingPowerDrawW;
    }

    public void setTrainingPowerDrawW(BigDecimal trainingPowerDrawW) {
        this.trainingPowerDrawW = trainingPowerDrawW;
    }

    public String getTrainingComputeEstimationMethod() {
        return trainingComputeEstimationMethod;
    }

    public void setTrainingComputeEstimationMethod(String trainingComputeEstimationMethod) {
        this.trainingComputeEstimationMethod = trainingComputeEstimationMethod;
    }

    public String getHuggingFaceDeveloperId() {
        return huggingFaceDeveloperId;
    }

    public void setHuggingFaceDeveloperId(String huggingFaceDeveloperId) {
        this.huggingFaceDeveloperId = huggingFaceDeveloperId;
    }

    public BigDecimal getPostTrainingComputeFlop() {
        return postTrainingComputeFlop;
    }

    public void setPostTrainingComputeFlop(BigDecimal postTrainingComputeFlop) {
        this.postTrainingComputeFlop = postTrainingComputeFlop;
    }

    public String getPostTrainingComputeNotes() {
        return postTrainingComputeNotes;
    }

    public void setPostTrainingComputeNotes(String postTrainingComputeNotes) {
        this.postTrainingComputeNotes = postTrainingComputeNotes;
    }

    public BigDecimal getHardwareUtilizationHfu() {
        return hardwareUtilizationHfu;
    }

    public void setHardwareUtilizationHfu(BigDecimal hardwareUtilizationHfu) {
        this.hardwareUtilizationHfu = hardwareUtilizationHfu;
    }

    public Boolean getOpenModelWeights() {
        return openModelWeights;
    }

    public void setOpenModelWeights(Boolean openModelWeights) {
        this.openModelWeights = openModelWeights;
    }
}
