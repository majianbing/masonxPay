package com.masonx.rail;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RailServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(RailServiceApplication.class, args);
    }
}
