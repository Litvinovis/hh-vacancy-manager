package com.hh.gui.service;

import com.hh.gui.model.User;
import com.hh.gui.repository.SearchRepository;
import com.hh.gui.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Admin-only user management — AdminController enforces the role check. */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepo;
    private final SearchRepository searchRepo;
    private final AuthService authService;

    public UserService(UserRepository userRepo, SearchRepository searchRepo, AuthService authService) {
        this.userRepo = userRepo;
        this.searchRepo = searchRepo;
        this.authService = authService;
    }

    public List<User> listAll() {
        return userRepo.findAll();
    }

    /** @return the created user, plus the plaintext password if one was generated (not stored anywhere else). */
    public CreateResult create(String username, String password, String displayName, String role, String city) {
        if (userRepo.existsByUsername(username)) {
            throw new IllegalArgumentException("Логин уже занят");
        }
        String plainPassword = (password == null || password.isBlank()) ? authService.generatePassword() : password;

        User user = new User();
        user.setUsername(username.trim());
        user.setPasswordHash(authService.hash(plainPassword));
        user.setDisplayName(displayName != null && !displayName.isBlank() ? displayName.trim() : username.trim());
        user.setRole("admin".equals(role) ? "admin" : "user");
        user.setCity(city != null ? city : "");
        user.setActive(true);
        User saved = userRepo.save(user);
        log.info("Администратор создал пользователя: {}", saved.getUsername());
        return new CreateResult(saved, plainPassword);
    }

    public Optional<User> update(Long id, String displayName, String city, String role, Boolean active) {
        Optional<User> userOpt = userRepo.findById(id);
        if (userOpt.isEmpty()) return Optional.empty();
        User user = userOpt.get();
        if (displayName != null) user.setDisplayName(displayName);
        if (city != null) user.setCity(city);
        if (role != null) user.setRole("admin".equals(role) ? "admin" : "user");
        if (active != null) user.setActive(active);
        userRepo.update(user);
        return Optional.of(user);
    }

    /** @return the new plaintext password, or empty if the user doesn't exist. */
    public Optional<String> resetPassword(Long id) {
        Optional<User> userOpt = userRepo.findById(id);
        if (userOpt.isEmpty()) return Optional.empty();
        String newPassword = authService.generatePassword();
        userRepo.updatePasswordHash(id, authService.hash(newPassword));
        log.info("Администратор сбросил пароль для: {}", userOpt.get().getUsername());
        return Optional.of(newPassword);
    }

    public boolean delete(Long id) {
        if (userRepo.findById(id).isEmpty()) return false;
        searchRepo.deleteByUserId(id);
        userRepo.delete(id);
        return true;
    }

    public record CreateResult(User user, String plainPassword) {}
}
