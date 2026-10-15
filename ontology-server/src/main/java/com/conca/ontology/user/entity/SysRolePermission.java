package com.conca.ontology.user.entity;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

/**
 * 角色权限黑名单表实体（sys_role_permission）：三个 JSON 列经 JacksonTypeHandler
 * 与 List&lt;String&gt; 互转。autoResultMap 必开，否则查询时 typeHandler 不生效。
 *
 * @author tianbaoxing
 * @date 2026-10-10
 */
@TableName(value = "sys_role_permission", autoResultMap = true)
public class SysRolePermission {

    @TableId(value = "role", type = IdType.INPUT)
    private String role;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> deniedTools;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> deniedSkills;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> deniedSubagents;

    private LocalDateTime updateTime;

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public List<String> getDeniedTools() {
        return deniedTools;
    }

    public void setDeniedTools(List<String> deniedTools) {
        this.deniedTools = deniedTools;
    }

    public List<String> getDeniedSkills() {
        return deniedSkills;
    }

    public void setDeniedSkills(List<String> deniedSkills) {
        this.deniedSkills = deniedSkills;
    }

    public List<String> getDeniedSubagents() {
        return deniedSubagents;
    }

    public void setDeniedSubagents(List<String> deniedSubagents) {
        this.deniedSubagents = deniedSubagents;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
