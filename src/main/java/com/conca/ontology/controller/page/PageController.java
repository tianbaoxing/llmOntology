package com.conca.ontology.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面入口：/ → 控制台首页（静态页 static/pages/index.html）。
 * 页面通过 /api/** 接口操作数据；认证由 AuthInterceptor 统一拦截（/login 公开）。
 */
@Controller
public class PageController {

    /** 控制台首页（概览/概念层/图数据/知识库/备份按 hash 切换 tab） */
    @GetMapping("/")
    public String index() {
        return "forward:/pages/index.html";
    }

    /** 登录页（公开：初始化向导 / 登录 / 注册） */
    @GetMapping("/login")
    public String login() {
        return "forward:/pages/login.html";
    }

    /** AI 问答页（SSE 流式） */
    @GetMapping("/chat")
    public String chat() {
        return "forward:/pages/chat.html";
    }

    /** 用户管理页（仅 ADMIN，AuthInterceptor 校验） */
    @GetMapping("/users")
    public String users() {
        return "forward:/pages/users.html";
    }

    /** 实例列表页（?label= 按类筛选，?page= 分页） */
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
