package com.backend.auth;

import com.backend.auth.dto.LoginRequest;
import com.backend.auth.dto.LoginResponse;
import com.backend.auth.dto.SignupRequest;
import com.backend.auth.dto.SignupResponse;
import com.backend.auth.jwt.JwtTokenProvider;
import com.backend.exception.AuthenticationFailedException;
import com.backend.exception.DuplicateEmailException;
import com.backend.exception.DuplicateUsernameException;
import com.backend.user.User;
import com.backend.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (userRepository.existsByUsernameIgnoreCase(request.getUsername())) {
            throw new DuplicateUsernameException();
        }
        if (userRepository.existsByEmailIgnoreCase(request.getEmail())) {
            throw new DuplicateEmailException();
        }

        log.info(request.toString());
        log.info(request.getEmail() + " " + request.getUsername());
        log.info("testttt");

        String encodedPassword = passwordEncoder.encode(request.getPassword());
        User user = new User(request.getUsername(), request.getEmail().toLowerCase(), encodedPassword);
        User saved = userRepository.save(user);
        return new SignupResponse(saved.getId(), saved.getUsername(), saved.getEmail());
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        Optional<User> userOptional = userRepository.findByEmailIgnoreCase(request.getEmail());
        if (userOptional.isEmpty()) {
            throw new AuthenticationFailedException();
        }

        User user = userOptional.get();
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new AuthenticationFailedException();
        }

        String accessToken = jwtTokenProvider.createAccessToken(user);
        return new LoginResponse(accessToken, "Bearer");
    }
}
