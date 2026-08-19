package com.conca.ontology.controller.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 管理员页面入口：/admin → 管理控制台（静态页 static/admin/index.html）。
 * 页面通过 /api/** 接口操作数据；后续认证在此入口统一拦截。
 */
@Controller
public class AdminPageController {

    @GetMapping("/admin")
    public String admin() {
        return "forward:/admin/index.html";
    }
}
