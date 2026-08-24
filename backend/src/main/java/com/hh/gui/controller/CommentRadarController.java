package com.hh.gui.controller;

import com.hh.gui.ai.CommentRadarService;
import com.hh.gui.model.CommentRadarFinding;
import com.hh.gui.model.User;
import com.hh.gui.repository.CommentRadarRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-only surface for the VK "comment radar" findings — see CommentRadarService.
 * Every endpoint here only ever reads/annotates findings; nothing posts or comments
 * anywhere on VK's behalf. Same "check isAdmin() per method" style as the rest of the
 * admin-facing controllers (AdminController/PipelineController) — not worth a second
 * interceptor layer for a handful of endpoints.
 */
@RestController
@RequestMapping("/api/radar")
public class CommentRadarController {

    private static final Logger log = LoggerFactory.getLogger(CommentRadarController.class);

    private final CommentRadarRepository radarRepo;
    private final CommentRadarService radarService;

    public CommentRadarController(CommentRadarRepository radarRepo, CommentRadarService radarService) {
        this.radarRepo = radarRepo;
        this.radarService = radarService;
    }

    /** GET /api/radar/findings?status=new&limit=50 */
    @GetMapping("/findings")
    public ResponseEntity<?> findings(@RequestParam(defaultValue = "new") String status,
                                       @RequestParam(defaultValue = "50") int limit,
                                       @RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) return forbidden();
        List<CommentRadarFinding> findings = radarRepo.findByStatus(status, limit);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("findings", findings);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/radar/{id}/status  body: {status: "sent"|"rejected", draftReply?: "..."}
     * draftReply is optional — only sent when the admin edited the AI draft before
     * marking the decision, otherwise the stored draft is left untouched.
     */
    @PostMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable long id, @RequestBody Map<String, Object> body,
                                           @RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) return forbidden();
        Object statusVal = body.get("status");
        if (!(statusVal instanceof String status)
                || !(CommentRadarFinding.STATUS_SENT.equals(status) || CommentRadarFinding.STATUS_REJECTED.equals(status))) {
            return ResponseEntity.badRequest().body(Map.of("error", "status должен быть 'sent' или 'rejected'"));
        }
        Object draftReplyVal = body.get("draftReply");
        String draftReply = draftReplyVal instanceof String s ? s : null;

        int updated = radarRepo.updateStatus(id, status, draftReply);
        if (updated == 0) return ResponseEntity.status(404).body(Map.of("error", "Находка не найдена"));
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * POST /api/radar/scan-now — manual trigger of one scan pass, same reasoning as
     * PipelineController's manual pipeline-run endpoints: verifying the end-to-end path
     * shouldn't require waiting for the 5-hour scheduled tick.
     */
    @PostMapping("/scan-now")
    public ResponseEntity<?> scanNow(@RequestAttribute("currentUser") User currentUser) {
        if (!currentUser.isAdmin()) return forbidden();
        try {
            int saved = radarService.scanAll();
            return ResponseEntity.ok(Map.of("status", "ok", "saved", saved));
        } catch (Exception e) {
            log.error("Ручной запуск радара VK завершился ошибкой: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "Требуются права администратора"));
    }
}
