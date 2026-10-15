package com.conca.ontology.file.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.conca.ontology.file.entity.FileMetadataRow;

/**
 * file_metadata 表 Mapper（@Mapper 由 MyBatis-Plus starter 自动装配扫描）
 *
 * @author tianbaoxing
 * @date 2026-10-10
 */
@Mapper
public interface FileMetadataMapper extends BaseMapper<FileMetadataRow> {
}
