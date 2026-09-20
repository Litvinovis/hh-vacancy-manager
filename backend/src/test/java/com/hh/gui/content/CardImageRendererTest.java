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
    void emptyTitle_fallsBackInsteadOfCrashing() throws Exception {
        Vacancy v = new Vacancy();   // ни названия, ни зарплаты, ни компании
        assertTrue(CardImageRenderer.vacancyCard(v, "x").length > 1000);
    }
}
