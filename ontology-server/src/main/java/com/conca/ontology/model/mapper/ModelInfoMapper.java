package com.conca.ontology.model.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.conca.ontology.model.entity.ModelInfoEntity;

/**
 * model_info 表 Mapper（@Mapper 由 MyBatis-Plus starter 自动装配扫描）
 *
 * @author tianbaoxing
 * @date 2026-10-29
 */
@Mapper
public interface ModelInfoMapper extends BaseMapper<ModelInfoEntity> {
}
