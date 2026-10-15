package com.conca.ontology.file.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 上传文件元数据行（file_metadata，P4：每文件一行，无全局 version）。
 * 自增 id 兼作列表顺序：同名覆盖保留原位置（updateById 不动 id），新文件追加尾部。
 * name 唯一键（相对 upload-dir，可含子目录）；转换四列可空，NULL 表示待对账回填
 * （updateStrategy=IGNORED 保证 updateById 能把已清空的列写回 NULL）。
 *
 * @author tianbaoxing
 * @date 2026-10-10
 */
@TableName("file_metadata")
public class FileMetadataRow {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 文件名（相对 upload-dir，'/' 分隔，唯一键） */
    private String name;

    private String path;

    private Long size;

    /** 扩展名（小写，无点） */
    private String ext;

    /** 文件说明（可为空字符串） */
    private String description;

    private LocalDateTime uploadedAt;

    /** 对应 Markdown 文件名（仅二进制转换文件有值） */
    @TableField(value = "md_name", updateStrategy = FieldStrategy.IGNORED)
    private String mdName;

    /** 转换状态：NONE / SUCCESS / FAILED（NULL=待对账回填） */
    @TableField(value = "convert_status", updateStrategy = FieldStrategy.IGNORED)
    private String convertStatus;

    /** 转换失败原因（仅 FAILED 时有值） */
    @TableField(value = "convert_msg", updateStrategy = FieldStrategy.IGNORED)
    private String convertMsg;

    private LocalDateTime convertedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public String getExt() {
        return ext;
    }

    public void setExt(String ext) {
        this.ext = ext;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public String getMdName() {
        return mdName;
    }

    public void setMdName(String mdName) {
        this.mdName = mdName;
    }

    public String getConvertStatus() {
        return convertStatus;
    }

    public void setConvertStatus(String convertStatus) {
        this.convertStatus = convertStatus;
    }

    public String getConvertMsg() {
        return convertMsg;
    }

    public void setConvertMsg(String convertMsg) {
        this.convertMsg = convertMsg;
    }

    public LocalDateTime getConvertedAt() {
        return convertedAt;
    }

    public void setConvertedAt(LocalDateTime convertedAt) {
        this.convertedAt = convertedAt;
    }
}
