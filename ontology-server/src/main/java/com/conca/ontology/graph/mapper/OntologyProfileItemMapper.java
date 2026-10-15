package com.conca.ontology.graph.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.conca.ontology.graph.entity.OntologyProfileItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 问询记录行 Mapper（ontology_profile，每条问答一行）
 *
 * @author tianbaoxing
 * @date 2026-10-10
 */
@Mapper
public interface OntologyProfileItemMapper extends BaseMapper<OntologyProfileItem> {
}
