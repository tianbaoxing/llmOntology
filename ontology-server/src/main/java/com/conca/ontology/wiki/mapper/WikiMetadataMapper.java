package com.conca.ontology.wiki.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.conca.ontology.wiki.entity.WikiMetadata;

/**
 * wiki_metadata 表 Mapper（@Mapper 由 MyBatis-Plus starter 自动装配扫描）
 *
 * @author tianbaoxing
 * @date 2026-10-24
 */
@Mapper
public interface WikiMetadataMapper extends BaseMapper<WikiMetadata> {
}
