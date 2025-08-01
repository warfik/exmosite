package com.crypto;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PortfolioDashboardApp {
    public static void main(String[] args) {
        SpringApplication.run(PortfolioDashboardApp.class, args);
    }
}
