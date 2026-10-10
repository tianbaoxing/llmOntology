package com.conca.ontology.dedup.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.conca.ontology.dedup.entity.RuleDedup;

/** rule_dedup 表 Mapper（@Mapper 由 MyBatis-Plus starter 自动装配扫描） */
@Mapper
public interface RuleDedupMapper extends BaseMapper<RuleDedup> {
}
