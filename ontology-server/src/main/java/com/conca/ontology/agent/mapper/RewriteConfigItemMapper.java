package com.conca.ontology.agent.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.conca.ontology.agent.entity.RewriteConfigItem;

/** rewrite_config 表 Mapper（@Mapper 由 MyBatis-Plus starter 自动装配扫描） */
@Mapper
public interface RewriteConfigItemMapper extends BaseMapper<RewriteConfigItem> {
}
