package com.substring.chat.New_Chatapp_backend;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

@SpringBootTest
class NewChatappBackendApplicationTests {

	@Autowired
	private MongoTemplate mongoTemplate;

	@Test
	void contextLoads() {
		org.junit.jupiter.api.Assertions.assertNotNull(mongoTemplate);
	}

}

