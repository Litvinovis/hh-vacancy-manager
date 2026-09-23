package com.hh.gui.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VkEngagementTrackerTest {

    @Test
    void postType_comesFromOurOwnRecords() {
        Map<String, Integer> sizes = Map.of("100", 1, "101", 6);
        Map<String, String> kinds = Map.of("200", "article", "201", "poll");
        assertEquals("vacancy", VkEngagementTracker.typeOf("100", sizes, kinds));
        assertEquals("digest", VkEngagementTracker.typeOf("101", sizes, kinds), "несколько вакансий под одним post_id — подборка");
        assertEquals("article", VkEngagementTracker.typeOf("200", sizes, kinds));
        assertEquals("poll", VkEngagementTracker.typeOf("201", sizes, kinds));
        assertEquals("other", VkEngagementTracker.typeOf("999", sizes, kinds), "пост не из очереди — например, ручной или старый");
    }
}
