package com.conca.ontology.shared.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面入口：/ → 控制台首页（静态页 static/pages/index.html）。
 * 页面通过 /api/** 接口操作数据；认证由 AuthInterceptor 统一拦截（/login 公开）。
 */

/**
 * @author tianbaoxing
 * @date 2026-08-21
 */
@Controller
public class PageController {

    /** 控制台首页（概览） */
    @GetMapping("/")
    public String index() {
        return "forward:/pages/index.html";
    }

    /** 登录页（公开：初始化向导 / 登录 / 注册） */
    @GetMapping("/login")
    public String login() {
        return "forward:/pages/login.html";
    }

    /** 图谱管理页（图数据 + 概念层，管理员功能，页面级权限由前端控制，接口层 403 兜底） */
    @GetMapping("/data")
    public String data() {
        return "forward:/pages/data.html";
    }

    /** 知识库页（文件管理） */
    @GetMapping("/files")
    public String files() {
        return "forward:/pages/files.html";
    }

    /** Wiki 知识库页（files-md/ 转换产物的只读呈现，仅 ADMIN） */
    @GetMapping("/wiki")
    public String wiki() {
        return "forward:/pages/wiki.html";
    }

    /** AI 问答页（SSE 流式） */
    @GetMapping("/chat")
    public String chat() {
        return "forward:/pages/chat.html";
    }

    /** 智能体搭建页（向导 + 列表 + 试运行，仅 ADMIN，AuthInterceptor 校验） */
    @GetMapping("/agents")
    public String agents() {
        return "forward:/pages/agents.html";
    }

    /** 驾驶舱页（AI 用量统计，页面级权限由前端控制，接口层 403 兜底） */
    @GetMapping("/dashboard")
    public String dashboard() {
        return "forward:/pages/dashboard.html";
    }

    /** 会话详情页（驾驶舱会话用量明细下钻：上下文构成双图 + 消息明细） */
    @GetMapping("/session-detail")
    public String sessionDetail() {
        return "forward:/pages/session-detail.html";
    }

    /** 评测中心页（数据集驱动问答评测，登录即可） */
    @GetMapping("/eval")
    public String eval() {
        return "forward:/pages/eval.html";
    }

    /** 用户管理页（仅 ADMIN，AuthInterceptor 校验） */
    @GetMapping("/users")
    public String users() {
        return "forward:/pages/users.html";
    }

    /** 实例列表页（?label= 按类筛选，?page= 分页，?q= 关键词搜索） */
    @GetMapping("/instances")
    public String instances() {
        return "forward:/pages/instances.html";
    }

    /** 实例图谱浏览页（?label=&key= 定位中心节点） */
    @GetMapping("/graph")
    public String graph() {
        return "forward:/pages/graph.html";
    }
}
