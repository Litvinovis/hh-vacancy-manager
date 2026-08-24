package com.hh.gui.controller;

import com.hh.gui.ai.CommentRadarService;
import com.hh.gui.client.VkReaderClient;
import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.CommentRadarFinding;
import com.hh.gui.model.User;
import com.hh.gui.repository.CommentRadarRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Hand-written fakes, no Mockito, no Spring context — same style as
 *  PipelineControllerTest/SettingsControllerTest. */
class CommentRadarControllerTest {

    private static User admin() {
        User u = new User();
        u.setId(1L);
        u.setRole("admin");
        return u;
    }

    private static User regular() {
        User u = new User();
        u.setId(2L);
        u.setRole("user");
        return u;
    }

    private static CommentRadarFinding finding(long id, String status) {
        CommentRadarFinding f = new CommentRadarFinding();
        f.setId(id);
        f.setSourceRef("241042323");
        f.setPostId("wall-241042323_" + id);
        f.setPostLink("https://vk.com/wall-241042323_" + id);
        f.setPostText("Где искать удалённую работу?");
        f.setMatchedKeyword("где искать удалённую работу");
        f.setDraftReply("Загляните в наше сообщество!");
        f.setStatus(status);
        return f;
    }

    private static class FakeRepo extends CommentRadarRepository {
        List<CommentRadarFinding> findings = new ArrayList<>();
        String lastFindByStatusArg;
        Long lastUpdateId;
        String lastUpdateStatus;
        String lastUpdateDraftReply;
        int updateReturnValue = 1;

        FakeRepo() { super(null); }

        @Override
        public List<CommentRadarFinding> findByStatus(String status, int limit) {
            lastFindByStatusArg = status;
            return findings;
        }

        @Override
        public int updateStatus(long id, String status, String draftReply) {
            lastUpdateId = id;
            lastUpdateStatus = status;
            lastUpdateDraftReply = draftReply;
            return updateReturnValue;
        }
    }

    private static class FakeService extends CommentRadarService {
        int scanCalls = 0;
        int scanResult = 0;
        boolean throwOnScan = false;
        FakeService() { super(new VkReaderClient(), null, null, new RuntimeConfig()); }
        @Override
        public int scanAll() {
            scanCalls++;
            if (throwOnScan) throw new RuntimeException("boom");
            return scanResult;
        }
    }

    private FakeRepo repo;
    private FakeService service;
    private CommentRadarController controller;

    private void init() {
        repo = new FakeRepo();
        service = new FakeService();
        controller = new CommentRadarController(repo, service);
    }

    // ── GET /findings ──

    @Test
    void findings_nonAdmin_returns403() {
        init();
        var response = controller.findings("new", 50, regular());
        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void findings_admin_returnsListFromRepoWithGivenStatus() {
        init();
        repo.findings = List.of(finding(1, "new"), finding(2, "new"));

        var response = controller.findings("new", 50, admin());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("new", repo.lastFindByStatusArg);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        List<CommentRadarFinding> found = (List<CommentRadarFinding>) body.get("findings");
        assertEquals(2, found.size());
    }

    // ── POST /{id}/status ──

    @Test
    void updateStatus_nonAdmin_returns403WithoutTouchingRepo() {
        init();
        var response = controller.updateStatus(1, Map.of("status", "sent"), regular());
        assertEquals(403, response.getStatusCode().value());
        assertNull(repo.lastUpdateId);
    }

    @Test
    void updateStatus_sent_updatesRepoWithGivenStatus() {
        init();
        var response = controller.updateStatus(5, Map.of("status", "sent"), admin());
        assertEquals(200, response.getStatusCode().value());
        assertEquals(5L, repo.lastUpdateId);
        assertEquals("sent", repo.lastUpdateStatus);
        assertNull(repo.lastUpdateDraftReply, "draftReply не передан — не должен затирать сохранённый черновик");
    }

    @Test
    void updateStatus_rejected_updatesRepo() {
        init();
        controller.updateStatus(5, Map.of("status", "rejected"), admin());
        assertEquals("rejected", repo.lastUpdateStatus);
    }

    @Test
    void updateStatus_withEditedDraftReply_passesItThrough() {
        init();
        controller.updateStatus(5, Map.of("status", "sent", "draftReply", "Отредактированный текст"), admin());
        assertEquals("Отредактированный текст", repo.lastUpdateDraftReply);
    }

    @Test
    void updateStatus_invalidStatusValue_returns400WithoutTouchingRepo() {
        init();
        var response = controller.updateStatus(5, Map.of("status", "yolo"), admin());
        assertEquals(400, response.getStatusCode().value());
        assertNull(repo.lastUpdateId);
    }

    @Test
    void updateStatus_missingStatus_returns400() {
        init();
        var response = controller.updateStatus(5, Map.of(), admin());
        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void updateStatus_findingNotFound_returns404() {
        init();
        repo.updateReturnValue = 0;
        var response = controller.updateStatus(999, Map.of("status", "sent"), admin());
        assertEquals(404, response.getStatusCode().value());
    }

    // ── POST /scan-now ──

    @Test
    void scanNow_nonAdmin_returns403WithoutScanning() {
        init();
        var response = controller.scanNow(regular());
        assertEquals(403, response.getStatusCode().value());
        assertEquals(0, service.scanCalls);
    }

    @Test
    void scanNow_admin_triggersScanAndReturnsSavedCount() {
        init();
        service.scanResult = 3;

        var response = controller.scanNow(admin());

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, service.scanCalls);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(3, body.get("saved"));
    }

    @Test
    void scanNow_serviceThrows_returns500WithoutPropagating() {
        init();
        service.throwOnScan = true;

        var response = assertDoesNotThrow(() -> controller.scanNow(admin()));

        assertEquals(500, response.getStatusCode().value());
    }
}
