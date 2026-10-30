package com.conca.ontology.model.dto;

import java.util.List;

/**
 * 模型切换选项分页视图（chat 页底部模型名弹框数据源）。
 * 筛选维度与分页语义同模型管理页（model_info）。
 *
 * @author tianbaoxing
 * @date 2026-10-30
 */
public record ModelOptionPageDTO(
        List<ModelOptionDTO> rows,
        long total,
        int page,
        int pageSize) {
}
