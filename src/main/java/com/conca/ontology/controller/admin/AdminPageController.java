package com.conca.ontology.controller.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 管理员页面入口：/admin → 管理控制台（静态页 static/admin/index.html）。
 * 页面通过 /api/** 接口操作数据；认证由 AuthInterceptor 统一拦截（/admin/login 公开）。
 */
@Controller
public class AdminPageController {

    /** 根路径重定向到管理控制台（未登录时由拦截器接着跳登录页） */
    @GetMapping("/")
    public String root() {
        return "redirect:/admin";
    }

    @GetMapping("/admin")
    public String admin() {
        return "forward:/admin/index.html";
    }

    @GetMapping("/admin/chat")
    public String chat() {
        return "forward:/admin/chat.html";
    }

    /** 登录页（公开：初始化向导 / 登录 / 注册） */
    @GetMapping("/admin/login")
    public String login() {
        return "forward:/admin/login.html";
    }

    /** 用户管理页（仅 ADMIN，AuthInterceptor 校验） */
    @GetMapping("/admin/users")
    public String users() {
        return "forward:/admin/users.html";
    }
}
