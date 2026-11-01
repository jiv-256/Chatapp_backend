package com.substring.chat.New_Chatapp_backend;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import com.mongodb.client.MongoClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NewChatappBackendApplication {

	static {
		loadEnv();
	}

	private static void loadEnv() {
		File envFile = new File(".env");
		System.out.println(">>> [ENV LOADER] Checking .env at: " + envFile.getAbsolutePath() + " exists: " + envFile.exists());
		if (!envFile.exists()) {
			return;
		}
		try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				int eqIndex = line.indexOf('=');
				if (eqIndex > 0) {
					String key = line.substring(0, eqIndex).trim();
					String value = line.substring(eqIndex + 1).trim();
					if ((value.startsWith("\"") && value.endsWith("\"")) ||
						(value.startsWith("'") && value.endsWith("'"))) {
						if (value.length() >= 2) {
							value = value.substring(1, value.length() - 1);
						}
					}
					System.out.println(">>> [ENV LOADER] Found key: " + key + " (length: " + value.length() + ")");
					if (System.getProperty(key) == null) {
						System.setProperty(key, value);
					}
					if ("MONGO_URI".equalsIgnoreCase(key) || "MONGODB_URI".equalsIgnoreCase(key) || "spring.data.mongodb.uri".equalsIgnoreCase(key)) {
						if (System.getProperty("spring.mongodb.uri") == null) {
							System.setProperty("spring.mongodb.uri", value);
						}
						if (System.getProperty("spring.data.mongodb.uri") == null) {
							System.setProperty("spring.data.mongodb.uri", value);
						}
					}
				}
			}
		} catch (Exception e) {
			System.out.println(">>> [ENV LOADER] Error: " + e.getMessage());
		}
	}

	public static void main(String[] args) {
		SpringApplication.run(NewChatappBackendApplication.class, args);
	}
	@Bean
	CommandLineRunner runner(MongoClient mongoClient) {
		return args -> {
			System.out.println("=================================");
			System.out.println("CONNECTED TO: " + mongoClient.getClusterDescription());
			System.out.println("=================================");
		};
	}

}
