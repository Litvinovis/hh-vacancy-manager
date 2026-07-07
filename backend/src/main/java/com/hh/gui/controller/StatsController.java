package com.hh.gui.controller;

import com.hh.gui.model.User;
import com.hh.gui.service.StatsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class StatsController {

    private final StatsService statsService;

    @Autowired
    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(@RequestAttribute("currentUser") User currentUser) {
        Long scopedUserId = currentUser.isAdmin() ? null : currentUser.getId();
        return ResponseEntity.ok(statsService.getStats(scopedUserId));
    }
}
