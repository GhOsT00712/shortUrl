package com.ghost.urlshortner;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.mongodb.client.MongoClient;

@SpringBootTest
class UrlshortnerApplicationTests {

	@MockitoBean
	private UrlshortnerService service;

	@MockitoBean
	private MongoClient mongoClient;

	@Test
	void contextLoads() {
	}

}
