package com.elibrary;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.elibrary.repository")
public class ElibraryApplication {

    public static void main(String[] args) {
        SpringApplication.run(ElibraryApplication.class, args);
    }
}
