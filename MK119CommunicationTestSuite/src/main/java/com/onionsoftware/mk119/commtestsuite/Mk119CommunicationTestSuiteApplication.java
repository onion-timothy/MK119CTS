package com.onionsoftware.mk119.commtestsuite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import com.onionsoftware.mk119.commtestsuite.io.IOServer;

@SpringBootApplication
public class Mk119CommunicationTestSuiteApplication {

	public static void main(String[] args) {
		SpringApplication.run(Mk119CommunicationTestSuiteApplication.class, args);
	}
	
	@EventListener(ApplicationReadyEvent.class)
	public void afterStart() {
		IOServer server = new IOServer(1502);
		server.start();
	}
}
