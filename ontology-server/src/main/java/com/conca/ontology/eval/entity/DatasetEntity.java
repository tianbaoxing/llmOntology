package com.conca.ontology.eval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 评测数据集行（dataset，P6 起替代 data/eval/datasets/{name}.jsonl 文件）。
 * 单列业务键：name = 原 .jsonl 文件名（eval_run.dataset 同名引用）；
 * 样本数走 COUNT(*) 派生，空数据集 = 本表有行而 dataset_sample 无行。
 */
@TableName("dataset")
public class DatasetEntity {

    @TableId(value = "name", type = IdType.INPUT)
    private String name;

    public DatasetEntity() {
    }

    public DatasetEntity(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
