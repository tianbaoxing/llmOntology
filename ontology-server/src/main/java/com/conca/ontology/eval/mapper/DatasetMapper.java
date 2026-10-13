package com.conca.ontology.eval.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.conca.ontology.eval.entity.DatasetEntity;

@Mapper
public interface DatasetMapper extends BaseMapper<DatasetEntity> {
}
