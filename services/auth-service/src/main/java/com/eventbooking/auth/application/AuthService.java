package com.eventbooking.auth.application;

import com.eventbooking.auth.api.dto.AuthResponse;
import com.eventbooking.auth.api.dto.LoginRequest;
import com.eventbooking.auth.api.dto.RegisterRequest;
import com.eventbooking.auth.config.JwtProperties;
import com.eventbooking.auth.domain.RefreshToken;
import com.eventbooking.auth.domain.Role;
import com.eventbooking.auth.domain.User;
import com.eventbooking.auth.exception.EmailAlreadyExistsException;
import com.eventbooking.auth.exception.InvalidTokenException;
import com.eventbooking.auth.infrastructure.RefreshTokenRepository;
import com.eventbooking.auth.infrastructure.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class AuthService {
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final RefreshTokenRepository refreshTokenRepository;

    public AuthService(
            JwtService jwtService,
            JwtProperties jwtProperties,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            RefreshTokenRepository refreshTokenRepository
    ) {
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional
    public User register(RegisterRequest request) {
        String email = request.email().toLowerCase().trim();

        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException(email);
        }

        String hash = passwordEncoder.encode(request.password());
        User user = new User(email, hash, request.fullName().trim());

        User savedUser = userRepository.save(user);
        log.info("Registered new user with id={} and email={}", savedUser.getId(), maskEmail(email));

        return savedUser;
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }

    @Transactional
    public AuthResponse refresh(String refreshTokenValue) {
        RefreshToken stored = refreshTokenRepository.findByToken(refreshTokenValue)
                .orElseThrow(() -> new InvalidTokenException("Refresh token not recognize."));

        if(!stored.isUsable()) {
            log.warn("Reuse or expiry detected for all refresh token of user {}", stored.getUserId());
            refreshTokenRepository.revokeAllForUser(stored.getUserId());
            throw new InvalidTokenException("Refresh token is no longer valid");
        }

        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new InvalidTokenException("User no longer exists"));

        List<String> authorities = user.getRoles().stream().map(Role::asAuthority).toList();
        String accessToken = jwtService.generateAccessToken(user.getEmail(), authorities);
        String newRefreshToken = createRefreshToken(user.getId());

        return AuthResponse.of(accessToken, newRefreshToken, jwtProperties.accessTokenTtl().toSeconds());
    }

    public User login(LoginRequest request) {
        String email = request.email().toLowerCase().trim();

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.password())
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);
        log.info("Login successful for {}", maskEmail(email));

        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("No authenticated user"));
    }

    @Transactional
    public AuthResponse loginWithTokens(LoginRequest request) {
        String email = request.email().toLowerCase().trim();
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.password())
        );

        List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Not a authenticated user."));

        String accessToken = jwtService.generateAccessToken(email, authorities);
        String refreshToken = createRefreshToken(user.getId());

        log.info("Issued tokens for {}", maskEmail(email));

        return AuthResponse.of(accessToken, refreshToken, jwtProperties.accessTokenTtl().toSeconds());
    }

    private String createRefreshToken(Long userId) {
        String token = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(jwtProperties.refreshTokenTtl());

        refreshTokenRepository.save(new RefreshToken(token, userId, expiresAt));
        return token;
    }

    @Transactional
    public void logout(String refreshTokenValue) {
        refreshTokenRepository.findByToken(refreshTokenValue)
                .ifPresent(token -> {
                    token.revoke();
                    log.info("Logout for user {}", token.getUserId());
                });
    }

    @Transactional
    public User grantAdmin(Long userId) {
        log.error("Service calling");
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found."));
        user.grantRole(Role.ADMIN);
        refreshTokenRepository.revokeAllForUser(userId);

        log.info("Granted ADMIN to user {}", userId);
        return user;
    }
}
