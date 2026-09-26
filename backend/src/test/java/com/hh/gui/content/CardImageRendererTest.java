package com.hh.gui.content;

import com.hh.gui.model.Vacancy;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardImageRendererTest {

    static { System.setProperty("java.awt.headless", "true"); }

    private static FontMetrics metrics() {
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setFont(new Font("DejaVu Sans", Font.BOLD, 64));
        return g.getFontMetrics();
    }

    @Test
    void vacancyCard_isValid1080SquarePng() throws Exception {
        Vacancy v = new Vacancy();
        v.setTitle("Ассистент руководителя"); v.setCompany("ООО Ромашка");
        v.setSalaryFrom(70000); v.setCurrency("RUR"); v.setNoveltyColor("green");
        byte[] png = CardImageRenderer.vacancyCard(v, "vk.com/remotevibe");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(1080, img.getWidth());
        assertEquals(1080, img.getHeight());
        assertTrue(png.length > 5_000, "картинка не должна быть пустой");
    }

    @Test
    void articleCard_rendersWithoutData() throws Exception {
        byte[] png = CardImageRenderer.articleCard("Как стать ассистентом без опыта", "разбор", "vk.com/remotevibe");
        assertEquals(1080, ImageIO.read(new ByteArrayInputStream(png)).getWidth());
    }

    @Test
    void wrap_breaksOnWords_andTruncatesWithEllipsis() {
        FontMetrics fm = metrics();
        List<String> lines = CardImageRenderer.wrap(
            "Очень длинное название вакансии которое точно не поместится в четыре строки на карточке потому что слов много", fm, 900, 2);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).endsWith("…"), lines.toString());
        for (String l : lines) assertTrue(fm.stringWidth(l) <= 900, "строка шире карточки: " + l);
    }

    @Test
    void wrap_shortTitle_singleLine_noEllipsis() {
        List<String> lines = CardImageRenderer.wrap("Ассистент", metrics(), 900, 4);
        assertEquals(List.of("Ассистент"), lines);
    }

    @Test
    void manropeIsLoadedFromResources_notSystemFallback() throws Exception {
        try (var in = CardImageRenderer.class.getResourceAsStream("/fonts/Manrope-800.ttf")) {
            assertTrue(in != null, "шрифт должен быть в ресурсах приложения");
            Font f = Font.createFont(Font.TRUETYPE_FONT, in);
            assertTrue(f.getFamily().startsWith("Manrope"), f.getFamily());
            assertTrue(f.canDisplayUpTo("Ассистент руководителя — ёЁ ₽") == -1, "кириллица, ё и ₽ в шрифте");
        }
    }

    @Test
    void everyThemeAndLayout_rendersValidCard() throws Exception {
        Vacancy v = new Vacancy();
        v.setTitle("Бизнес-ассистент собственника с очень длинным названием вакансии для проверки переносов");
        v.setCompany("ООО Ромашка"); v.setSalaryFrom(100000); v.setSalaryTo(120000); v.setCurrency("RUR");
        for (CardImageRenderer.Theme t : CardImageRenderer.THEMES) {
            for (CardImageRenderer.Layout l : CardImageRenderer.Layout.values()) {
                byte[] png = CardImageRenderer.vacancyCard(v, "vk.com/remotevibe", t, l);
                assertEquals(1080, ImageIO.read(new ByteArrayInputStream(png)).getWidth(), t.name() + "/" + l);
            }
        }
    }

    @Test
    void themeChoice_stablePerVacancy_variesAcrossVacancies() {
        java.util.Set<String> themes = new java.util.HashSet<>();
        for (long id = 1; id <= 40; id++) {
            Vacancy v = new Vacancy(); v.setId(id);
            String first = CardImageRenderer.themeFor(CardImageRenderer.seedOf(v)).name();
            assertEquals(first, CardImageRenderer.themeFor(CardImageRenderer.seedOf(v)).name(), "одна вакансия — одна тема");
            themes.add(first);
        }
        assertTrue(themes.size() >= 6, "лента должна быть разной, тем использовано: " + themes);
    }

    @Test
    void salary_numbersNeverSplitAcrossLines() {
        String s = CardImageRenderer.keepNumbersTogether("от 100 000 до 120 000 ₽");
        assertEquals("от 100\u00A0000 до 120\u00A0000\u00A0₽", s);
        // перенос по словам не может разорвать число: split по обычным пробелам его не видит
        assertTrue(java.util.Arrays.asList(s.split("\\s+")).contains("120\u00A0000\u00A0₽"));
    }

    @Test
    void emptyTitle_fallsBackInsteadOfCrashing() throws Exception {
        Vacancy v = new Vacancy();   // ни названия, ни зарплаты, ни компании
        assertTrue(CardImageRenderer.vacancyCard(v, "x").length > 1000);
    }
}
