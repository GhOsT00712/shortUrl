package com.ghost.urlshortner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the HTTP application and configures the MongoDB client from application properties. */
@SpringBootApplication
public class UrlshortnerApplication {

	/** Boots Spring, including service initialization and required MongoDB index creation. */
	public static void main(String[] args) {
		SpringApplication.run(UrlshortnerApplication.class, args);
	}

}
