package com.conca.ontology.graph.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.conca.ontology.graph.entity.GraphClass;

/**
 * graph_class 表 Mapper（@Mapper 由 MyBatis-Plus starter 自动装配扫描）
 *
 * @author tianbaoxing
 * @date 2026-10-10
 */
@Mapper
public interface GraphClassMapper extends BaseMapper<GraphClass> {
}
