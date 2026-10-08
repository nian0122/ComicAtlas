package com.comicatlas.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/** 漫画 AI 分析独立单体。 */
@SpringBootApplication
@EnableAsync
public class ComicAiApplication {
    public static void main(String[] arguments) { SpringApplication.run(ComicAiApplication.class, arguments); }
}
