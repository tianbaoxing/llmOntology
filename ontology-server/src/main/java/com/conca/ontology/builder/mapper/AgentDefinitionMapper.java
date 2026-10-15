package com.conca.ontology.builder.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.conca.ontology.builder.entity.AgentDefinitionEntity;

/**
 * agent_definition 表 Mapper（@Mapper 由 MyBatis-Plus starter 自动装配扫描）
 *
 * @author tianbaoxing
 * @date 2026-10-12
 */
@Mapper
public interface AgentDefinitionMapper extends BaseMapper<AgentDefinitionEntity> {
}
