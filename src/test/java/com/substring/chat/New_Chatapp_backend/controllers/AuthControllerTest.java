package com.substring.chat.New_Chatapp_backend.controllers;

import com.substring.chat.New_Chatapp_backend.entities.User;
import com.substring.chat.New_Chatapp_backend.repo.UserRepo;
import com.substring.chat.New_Chatapp_backend.security.UserPrincipal;
import com.substring.chat.New_Chatapp_backend.security.UserTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private MockMvc mockMvc;

    @Mock
    private UserRepo userRepo;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserTokenService userTokenService;

    private AuthController authController;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        authController = new AuthController(userRepo, passwordEncoder, userTokenService);
        mockMvc = MockMvcBuilders.standaloneSetup(authController).build();
    }

    @Test
    void testRegistrationSuccess() throws Exception {
        when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("secret123")).thenReturn("hashed_secret123");
        when(userRepo.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId("usr-100");
            return u;
        });

        String json = "{\"name\":\"Alice Wonderland\",\"email\":\"alice@example.com\",\"password\":\"secret123\",\"confirmPassword\":\"secret123\",\"avatar\":\"Felix\"}";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Registration successful. Please log in."))
                .andExpect(jsonPath("$.email").value("alice@example.com"));

        verify(userRepo, times(1)).save(any(User.class));
    }

    @Test
    void testRegistrationPasswordMismatch() throws Exception {
        String json = "{\"name\":\"Alice\",\"email\":\"alice@example.com\",\"password\":\"secret123\",\"confirmPassword\":\"different\",\"avatar\":\"Felix\"}";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Passwords do not match. They must match."));
    }

    @Test
    void testRegistrationMissingConfirmPassword() throws Exception {
        String json = "{\"name\":\"Alice\",\"email\":\"alice@example.com\",\"password\":\"secret123\",\"avatar\":\"Felix\"}";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Confirm password is required."));
    }

    @Test
    void testRegistrationDuplicateAccount() throws Exception {
        User existing = new User();
        existing.setEmail("alice@example.com");
        when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.of(existing));

        String json = "{\"name\":\"Alice\",\"email\":\"alice@example.com\",\"password\":\"secret123\",\"confirmPassword\":\"secret123\",\"avatar\":\"Felix\"}";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This email is already registered. Please log in."));
    }

    @Test
    void testLoginAccountNotFound() throws Exception {
        when(userRepo.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        String json = "{\"email\":\"nobody@example.com\",\"password\":\"secret123\"}";

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Account not found. Please register first."));
    }

    @Test
    void testLoginIncorrectPassword() throws Exception {
        User user = new User();
        user.setEmail("alice@example.com");
        user.setPassword("hashed_secret");

        when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongpassword", "hashed_secret")).thenReturn(false);

        String json = "{\"email\":\"alice@example.com\",\"password\":\"wrongpassword\"}";

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Incorrect password. Please try again."));
    }

    @Test
    void testLoginSuccessImmediatelyWithoutVerification() throws Exception {
        User user = new User();
        user.setId("usr-100");
        user.setEmail("alice@example.com");
        user.setName("Alice");
        user.setAvatar("Felix");
        user.setPassword("hashed_secret");

        when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret123", "hashed_secret")).thenReturn(true);
        when(userTokenService.generateToken(any(User.class))).thenReturn("valid.jwt.token");

        String json = "{\"email\":\"alice@example.com\",\"password\":\"secret123\"}";

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Login successful!"))
                .andExpect(jsonPath("$.token").value("valid.jwt.token"))
                .andExpect(jsonPath("$.user.name").value("Alice"))
                .andExpect(jsonPath("$.user.avatar").value("Felix"));
    }

    @Test
    void testGetMeWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void testGetMeWhenAuthenticated() throws Exception {
        User user = new User();
        user.setId("usr-456");
        user.setEmail("test@gmail.com");
        user.setName("Test User");
        user.setAvatar("Lorelei");

        UserPrincipal principal = UserPrincipal.create(user);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user.email").value("test@gmail.com"))
                .andExpect(jsonPath("$.user.name").value("Test User"))
                .andExpect(jsonPath("$.user.avatar").value("Lorelei"));
    }

    @Test
    void testLogoutClearsSession() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Logged out successfully"));
    }

    @Test
    void testUpdateAvatarSuccess() throws Exception {
        User user = new User();
        user.setId("usr-123");
        user.setEmail("alice@example.com");
        user.setName("Alice");
        user.setAvatar("Felix");

        UserPrincipal principal = UserPrincipal.create(user);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        when(userRepo.findById("usr-123")).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userTokenService.generateToken(any(User.class))).thenReturn("fresh-jwt-token");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/auth/avatar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"avatar\": \"Shadow\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.user.avatar").value("Shadow"))
                .andExpect(jsonPath("$.token").value("fresh-jwt-token"));
    }

    @Test
    void testUpdateAvatarUnauthenticated() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/auth/avatar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"avatar\": \"Shadow\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testUpdateAvatarEmptyAvatar() throws Exception {
        User user = new User();
        user.setId("usr-123");
        user.setEmail("alice@example.com");
        user.setName("Alice");
        user.setAvatar("Felix");

        UserPrincipal principal = UserPrincipal.create(user);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/auth/avatar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"avatar\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testLoginDatabaseUnavailableReturns503() throws Exception {
        when(userRepo.findByEmail("alice@example.com"))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Mongo connection timed out"));

        String json = "{\"email\":\"alice@example.com\",\"password\":\"secret123\"}";

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Database service is temporarily unavailable. Please try again shortly."));
    }

    @Test
    void testRegistrationDatabaseUnavailableReturns503() throws Exception {
        when(userRepo.findByEmail("alice@example.com"))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Mongo connection timed out"));

        String json = "{\"name\":\"Alice Wonderland\",\"email\":\"alice@example.com\",\"password\":\"secret123\",\"confirmPassword\":\"secret123\",\"avatar\":\"Felix\"}";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Database service is temporarily unavailable. Please try again in a few moments."));
    }
}
