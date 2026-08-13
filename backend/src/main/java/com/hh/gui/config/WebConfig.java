package com.hh.gui.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * No CORS mapping on purpose: the UI is served from this same application
 * (addResourceHandlers below), so every /api/** call is same-origin and needs no
 * cross-origin grant. The previous allowedOrigins("*") on /api/** granted nothing
 * the UI used and only widened the surface.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/");
    }
}
