package com.masonx.virtualaccount;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VirtualAccountApplication {
    public static void main(String[] args) {
        SpringApplication.run(VirtualAccountApplication.class, args);
    }
}
