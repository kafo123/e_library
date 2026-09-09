package com.elibrary.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("E-Library Service API")
                .description("电子图书馆服务:浏览、详情、借阅、归还、我的借阅")
                .version("1.0.0"));
    }
}
