package com.hh.gui.service;

import com.hh.gui.model.User;
import com.hh.gui.repository.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Optional;

@Service
public class AuthService {

    private static final String RANDOM_PASSWORD_CHARS =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepo;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository userRepo) {
        this.userRepo = userRepo;
    }

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String hash) {
        return hash != null && !hash.isEmpty() && encoder.matches(rawPassword, hash);
    }

    /** Returns the user if the username/password pair is valid and the account is active. */
    public Optional<User> authenticate(String username, String password) {
        if (username == null || password == null) return Optional.empty();
        Optional<User> userOpt = userRepo.findByUsername(username.trim());
        if (userOpt.isEmpty()) return Optional.empty();
        User user = userOpt.get();
        if (!user.isActive()) return Optional.empty();
        if (!matches(password, user.getPasswordHash())) return Optional.empty();
        return Optional.of(user);
    }

    /** 12-char random password for newly-seeded/created accounts. */
    public String generatePassword() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(RANDOM_PASSWORD_CHARS.charAt(RANDOM.nextInt(RANDOM_PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}
