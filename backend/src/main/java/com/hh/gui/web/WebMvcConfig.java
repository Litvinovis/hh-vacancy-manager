package com.hh.gui.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers session-based auth for the JSON API. No spring-boot-starter-security:
 * that starter's default-login-page/CSRF autoconfiguration fights this app's
 * session-cookie SPA + JSON API shape for no benefit at this scale — a plain
 * HandlerInterceptor over the servlet container's built-in HttpSession is enough.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebMvcConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns("/api/auth/**");
    }
}
