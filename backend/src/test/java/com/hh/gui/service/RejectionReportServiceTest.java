package com.hh.gui.service;

import com.hh.gui.model.Vacancy;
import com.hh.gui.repository.VacancyRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RejectionReportServiceTest {

    static class FakeRepo extends VacancyRepository {
        List<Vacancy> rejected = new ArrayList<>();
        String lastSince;
        FakeRepo() { super(null); }
        @Override
        public List<Vacancy> findRejectedSince(String since, int limit) {
            lastSince = since;
            return rejected;
        }
    }

    private Vacancy rejected(String hhId, String company, int score, String title) {
        Vacancy v = new Vacancy();
        v.setHhId(hhId);
        v.setCompany(company);
        v.setAiScore(score);
        v.setTitle(title);
        v.setModerationStatus("rejected");
        return v;
    }

    @Test
    void topPatterns_groupsByChannelAndEmployer_mostFrequentFirst() {
        FakeRepo repo = new FakeRepo();
        repo.rejected = List.of(
            rejected("tg_frilanser_vacansii_1", "@frilanser_vacansii", 10, "Курьер"),
            rejected("tg_frilanser_vacansii_2", "@frilanser_vacansii", 15, "Курьер (срочно)"),
            rejected("tg_frilanser_vacansii_3", "@frilanser_vacansii", 5, "Курьер"),
            rejected("135397710", "Т-Банк", 40, "Эксперт-расчетчик"));
        RejectionReportService svc = new RejectionReportService(repo);

        List<RejectionReportService.Pattern> patterns = svc.topPatterns(7, 20);

        assertEquals(2, patterns.size());
        RejectionReportService.Pattern top = patterns.get(0);
        assertEquals("frilanser_vacansii", top.channel());
        assertEquals("@frilanser_vacansii", top.employer());
        assertEquals(3, top.count(), "три отклонения одного канала/работодателя должны схлопнуться в одну строку");
        assertEquals(10.0, top.avgScore(), 0.01);
        assertEquals(2, top.sampleTitles().size(), "два УНИКАЛЬНЫХ заголовка среди трёх (один дублируется)");
    }

    @Test
    void topPatterns_hhRuSource_groupedUnderHhRuLabel() {
        FakeRepo repo = new FakeRepo();
        repo.rejected = List.of(rejected("135553293", "Т-Банк", 20, "Эксперт-расчетчик ОСАГО"));
        RejectionReportService svc = new RejectionReportService(repo);

        List<RejectionReportService.Pattern> patterns = svc.topPatterns(7, 20);

        assertEquals("hh.ru", patterns.get(0).channel(), "числовой hh_id без tg_-префикса — не Telegram-источник");
    }

    @Test
    void topPatterns_respectsLimit() {
        FakeRepo repo = new FakeRepo();
        repo.rejected = List.of(
            rejected("tg_a_1", "@a", 0, "X"),
            rejected("tg_b_1", "@b", 0, "Y"),
            rejected("tg_c_1", "@c", 0, "Z"));
        RejectionReportService svc = new RejectionReportService(repo);

        assertEquals(2, svc.topPatterns(7, 2).size());
    }

    @Test
    void topPatterns_emptyRejections_returnsEmptyList() {
        FakeRepo repo = new FakeRepo();
        RejectionReportService svc = new RejectionReportService(repo);

        assertTrue(svc.topPatterns(7, 20).isEmpty());
    }
}
