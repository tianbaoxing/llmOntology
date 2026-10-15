package com.conca.ontology.user.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册认证拦截器：覆盖页面路由、静态页面与 API（LLM 接口在拦截器内部放行，后续做 token 验证）
 *
 * @author tianbaoxing
 * @date 2026-08-21
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor())
                .addPathPatterns("/", "/login", "/data", "/files", "/wiki",
                        "/chat", "/agents", "/dashboard", "/users", "/instances", "/graph", "/pages/**", "/api/**");
    }
}
