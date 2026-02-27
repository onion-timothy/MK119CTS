package com.onionsoftware.mk119.commtestsuite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Mk119CommunicationTestSuiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(Mk119CommunicationTestSuiteApplication.class, args);
    }
}
