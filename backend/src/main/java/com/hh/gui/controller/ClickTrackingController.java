package com.hh.gui.controller;

import com.hh.gui.service.ClickTrackingService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Public, unauthenticated redirect for click-tracked "Откликнуться" links (see
 * ClickTrackingService) — outside /api/**, so WebMvcConfig's session-auth
 * interceptor never applies here, same as it can't for a reader who isn't logged
 * into this app at all. Deliberately minimal: one lookup, one insert, one redirect,
 * since it's on the hot path of every reader who actually clicks.
 */
@RestController
public class ClickTrackingController {

    private final ClickTrackingService clickTrackingService;

    public ClickTrackingController(ClickTrackingService clickTrackingService) {
        this.clickTrackingService = clickTrackingService;
    }

    @GetMapping("/go/{token}")
    public ResponseEntity<Void> redirect(@PathVariable String token) {
        Optional<String> url = clickTrackingService.resolveAndRecordClick(token);
        if (url.isEmpty() || url.get().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, url.get()).build();
    }
}
