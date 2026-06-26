package com.hh.gui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HhGuiApplication {

    public static void main(String[] args) {
        SpringApplication.run(HhGuiApplication.class, args);
    }
}
