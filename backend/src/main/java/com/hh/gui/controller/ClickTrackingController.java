package com.hh.gui.controller;

import com.hh.gui.service.ClickRateLimiter;
import com.hh.gui.service.ClickTrackingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
 * since it's on the hot path of every reader who actually clicks. Gated by
 * ClickRateLimiter first — open + unauthenticated is exactly the shape that invites
 * a scripted flood, so that check runs before either DB call.
 */
@RestController
public class ClickTrackingController {

    private final ClickTrackingService clickTrackingService;
    private final ClickRateLimiter rateLimiter;

    public ClickTrackingController(ClickTrackingService clickTrackingService, ClickRateLimiter rateLimiter) {
        this.clickTrackingService = clickTrackingService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/go/{token}")
    public ResponseEntity<Void> redirect(@PathVariable String token, HttpServletRequest request) {
        if (!rateLimiter.allow(request.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        Optional<String> url = clickTrackingService.resolveAndRecordClick(token);
        if (url.isEmpty() || url.get().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, url.get()).build();
    }
}
