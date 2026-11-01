package com.substring.chat.New_Chatapp_backend.security;

import com.substring.chat.New_Chatapp_backend.entities.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Date;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class SecurityEndpointsTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserTokenService userTokenService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void testUnauthenticatedAccessToRoomsIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\":\"test-room\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    void testGetAuthMeWhenUnauthenticatedReturns200WithFalse() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.user").doesNotExist());
    }

    @Test
    void testGetAuthMeWithValidJwtReturnsAuthenticatedUser() throws Exception {
        User user = new User();
        user.setId("test-user-id-99");
        user.setEmail("verified.user@gmail.com");
        user.setName("Verified User");
        user.setPicture("https://avatar.url/pic.jpg");
        user.setGoogleId("google-sub-999");
        user.setCreatedAt(new Date());

        String token = userTokenService.generateToken(user);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user.email").value("verified.user@gmail.com"))
                .andExpect(jsonPath("$.user.name").value("Verified User"))
                .andExpect(jsonPath("$.user.picture").value("https://avatar.url/pic.jpg"));
    }
}
