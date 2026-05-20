package com.kwcode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 天工开物 - Coding Agent 主入口
 * @origin Python: cli/main.py (typer app)
 */
@SpringBootApplication
public class KwcodeApplication {

    public static void main(String[] args) {
        SpringApplication.run(KwcodeApplication.class, args);
    }
}
