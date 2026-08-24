package com.hh.gui.ai;

import com.hh.gui.client.VkReaderClient;
import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.CommentRadarFinding;
import com.hh.gui.repository.CommentRadarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hand-written fakes, no Mockito — same style as PipelineControllerTest/SettingsControllerTest:
 * extend the real class, override just the methods this service actually calls, record what
 * happened in plain fields.
 */
class CommentRadarServiceTest {

    private static class FakeVkReaderClient extends VkReaderClient {
        final Map<String, List<VkPost>> postsBySource = new HashMap<>();
        @Override
        public List<VkPost> fetchWallPosts(String communityId, int count) {
            return postsBySource.getOrDefault(communityId, List.of());
        }
    }

    private static class FakeRepo extends CommentRadarRepository {
        final List<CommentRadarFinding> saved = new ArrayList<>();
        final Set<String> existingPostIds = new HashSet<>();
        FakeRepo() { super(null); }
        @Override
        public boolean existsByPlatformAndPostId(String platform, String postId) {
            return existingPostIds.contains(postId);
        }
        @Override
        public CommentRadarFinding save(CommentRadarFinding f) {
            saved.add(f);
            return f;
        }
    }

    private static class FakeAnalyzer extends VacancyAiAnalyzer {
        String lastPrompt;
        String draftContent = "Загляните в сообщество, там свежие вакансии!";
        boolean throwOnCall = false;
        FakeAnalyzer() { super(new RuntimeConfig(), null, null, null); }
        @Override
        String callLlm(String prompt, int maxTokens, String modelOverride) throws Exception {
            lastPrompt = prompt;
            if (throwOnCall) throw new RuntimeException("boom");
            return "{\"choices\":[{\"message\":{\"content\":\"" + draftContent + "\"}}]}";
        }
    }

    private FakeVkReaderClient vk;
    private FakeRepo repo;
    private FakeAnalyzer analyzer;
    private RuntimeConfig config;
    private CommentRadarService service;

    @BeforeEach
    void setUp() {
        vk = new FakeVkReaderClient();
        repo = new FakeRepo();
        analyzer = new FakeAnalyzer();
        config = new RuntimeConfig();
        service = new CommentRadarService(vk, repo, analyzer, config);
    }

    private static VkReaderClient.VkPost post(String id, String text) {
        return new VkReaderClient.VkPost("wall-1_" + id, text, 1700000000L, "https://vk.com/wall-1_" + id);
    }

    @Test
    void scanAll_noSourcesConfigured_doesNothing() {
        config.setVkRadarSourceGroups("");

        assertEquals(0, service.scanAll());
        assertTrue(repo.saved.isEmpty());
    }

    @Test
    void scanAll_matchingKeyword_savesFindingWithDraft() {
        config.setVkRadarSourceGroups("1");
        vk.postsBySource.put("1", List.of(post("100", "Ребят, где искать удалённую работу вообще?")));

        int saved = service.scanAll();

        assertEquals(1, saved);
        assertEquals(1, repo.saved.size());
        CommentRadarFinding f = repo.saved.get(0);
        assertEquals("wall-1_100", f.getPostId());
        assertEquals("1", f.getSourceRef());
        assertEquals("где искать удалённую работу", f.getMatchedKeyword());
        assertEquals("Загляните в сообщество, там свежие вакансии!", f.getDraftReply());
        assertEquals(CommentRadarFinding.STATUS_NEW, f.getStatus());
    }

    @Test
    void scanAll_noKeywordMatch_notSaved() {
        config.setVkRadarSourceGroups("1");
        vk.postsBySource.put("1", List.of(post("101", "Продаю велосипед, торг уместен")));

        assertEquals(0, service.scanAll());
        assertTrue(repo.saved.isEmpty());
    }

    @Test
    void scanAll_alreadyKnownPost_skippedWithoutCallingAi() {
        config.setVkRadarSourceGroups("1");
        vk.postsBySource.put("1", List.of(post("102", "Где искать удалённую работу?")));
        repo.existingPostIds.add("wall-1_102");

        assertEquals(0, service.scanAll());
        assertNull(analyzer.lastPrompt, "уже известный пост не должен доходить до AI-вызова");
    }

    @Test
    void scanAll_aiCallFails_stillSavesFindingWithBlankDraft() {
        config.setVkRadarSourceGroups("1");
        vk.postsBySource.put("1", List.of(post("103", "Посоветуйте паблик с вакансиями")));
        analyzer.throwOnCall = true;

        int saved = service.scanAll();

        assertEquals(1, saved);
        assertEquals("", repo.saved.get(0).getDraftReply(),
            "сбой AI не должен терять находку — черновик остаётся пустым для ручного заполнения");
    }

    @Test
    void scanAll_multipleSourceCommunities_scansEachOne() {
        config.setVkRadarSourceGroups("1, 2");
        vk.postsBySource.put("1", List.of(post("200", "Ищу удалёнку, посоветуйте")));
        vk.postsBySource.put("2", List.of(post("300", "Ищу удалёнку тоже")));

        assertEquals(2, service.scanAll());
    }

    @Test
    void matchKeyword_caseInsensitive() {
        assertNotNull(CommentRadarService.matchKeyword("ГДЕ ИСКАТЬ ВАКАНСИИ на удалёнке?"));
    }

    @Test
    void matchKeyword_noMatch_returnsNull() {
        assertNull(CommentRadarService.matchKeyword("Красивый закат сегодня"));
    }

    @Test
    void matchKeyword_blankText_returnsNull() {
        assertNull(CommentRadarService.matchKeyword(""));
        assertNull(CommentRadarService.matchKeyword(null));
    }
}
