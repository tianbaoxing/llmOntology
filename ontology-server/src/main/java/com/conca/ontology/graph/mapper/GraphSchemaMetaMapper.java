package com.conca.ontology.graph.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.conca.ontology.graph.entity.GraphSchemaMeta;

/** graph_schema_meta 表 Mapper（@Mapper 由 MyBatis-Plus starter 自动装配扫描） */
@Mapper
public interface GraphSchemaMetaMapper extends BaseMapper<GraphSchemaMeta> {
}
