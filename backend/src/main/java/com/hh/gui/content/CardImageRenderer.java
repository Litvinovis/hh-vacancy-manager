package com.hh.gui.content;

import com.hh.gui.model.Vacancy;
import com.hh.gui.util.SalaryFormatter;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Карточка-картинка к посту VK, 1080×1080, рисуется здесь же (Java2D, headless).
 *
 * Зачем своя, а не стоковая: пост с картинкой в умной ленте ВК стабильно обгоняет голый
 * текст, а стоковые фото к вакансиям выглядят чужими и требуют внешнего API с ключом.
 * Карточка с названием, зарплатой и форматом работы читается в ленте за секунду и делает
 * посты узнаваемыми. Цветовой акцент — по оценке модели (зелёный/жёлтый/красный), той же,
 * что в тексте поста.
 *
 * Только DejaVu Sans: единственный шрифт с кириллицей, который гарантированно есть на
 * сервере (Ubuntu Server без пакетов шрифтов).
 */
public final class CardImageRenderer {
    private CardImageRenderer() {}

    static final int SIZE = 1080;
    private static final String FONT = "DejaVu Sans";
    private static final Color INK = new Color(0x1B, 0x1F, 0x24);
    private static final Color MUTED = new Color(0x5A, 0x62, 0x6B);
    private static final Color PAPER = new Color(0xF7, 0xF8, 0xFA);

    /** Карточка вакансии: акцент по оценке, название, зарплата, «удалённо», подпись сообщества. */
    public static byte[] vacancyCard(Vacancy v, String communityName) throws IOException {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        setup(g);

        Color accent = accentFor(v.getNoveltyColor());
        g.setPaint(new GradientPaint(0, 0, PAPER, 0, SIZE, Color.WHITE));
        g.fillRect(0, 0, SIZE, SIZE);
        g.setColor(accent);
        g.fillRect(0, 0, SIZE, 28);                       // полоса-акцент сверху

        int y = 200;
        g.setColor(MUTED);
        g.setFont(new Font(FONT, Font.PLAIN, 34));
        g.drawString("УДАЛЁННАЯ РАБОТА", 80, y);

        y += 110;
        g.setColor(INK);
        g.setFont(new Font(FONT, Font.BOLD, 64));
        y = drawWrapped(g, safe(v.getTitle(), "Вакансия"), 80, y, SIZE - 160, 4);

        y += 90;
        String salary = SalaryFormatter.hasSalary(v) ? SalaryFormatter.forReport(v) : "зарплата в описании";
        g.setColor(accent.darker());
        g.setFont(new Font(FONT, Font.BOLD, 52));
        y = drawWrapped(g, salary, 80, y, SIZE - 160, 2);

        if (v.getCompany() != null && !v.getCompany().isBlank() && !v.getCompany().startsWith("@")) {
            y += 70;
            g.setColor(MUTED);
            g.setFont(new Font(FONT, Font.PLAIN, 40));
            y = drawWrapped(g, v.getCompany(), 80, y, SIZE - 160, 2);
        }

        footer(g, communityName);
        g.dispose();
        return png(img);
    }

    /** Карточка статьи/опроса: тёмный фон, крупный заголовок, подпись сообщества. */
    public static byte[] articleCard(String title, String kicker, String communityName) throws IOException {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        setup(g);
        g.setPaint(new GradientPaint(0, 0, new Color(0x1F, 0x2A, 0x44), SIZE, SIZE, new Color(0x0F, 0x14, 0x22)));
        g.fillRect(0, 0, SIZE, SIZE);

        int y = 160;
        g.setColor(new Color(0x9F, 0xB3, 0xD9));
        g.setFont(new Font(FONT, Font.PLAIN, 36));
        g.drawString(kicker.toUpperCase(), 80, y);

        y += 100;
        g.setColor(Color.WHITE);
        g.setFont(new Font(FONT, Font.BOLD, 72));
        drawWrapped(g, title, 80, y, SIZE - 160, 6);

        g.setColor(new Color(0x9F, 0xB3, 0xD9));
        g.setStroke(new BasicStroke(4));
        g.drawLine(80, SIZE - 140, 260, SIZE - 140);
        g.setFont(new Font(FONT, Font.PLAIN, 36));
        g.drawString(communityName, 80, SIZE - 80);
        g.dispose();
        return png(img);
    }

    /**
     * Карточка подборки: «N вакансий» крупно и первые названия списком — чтобы по картинке
     * в ленте было видно, что внутри не одна вакансия, а выбор.
     */
    public static byte[] digestCard(List<Vacancy> vacancies, String kicker, String communityName) throws IOException {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        setup(g);
        g.setPaint(new GradientPaint(0, 0, PAPER, 0, SIZE, Color.WHITE));
        g.fillRect(0, 0, SIZE, SIZE);
        Color accent = accentFor(null);
        g.setColor(accent);
        g.fillRect(0, 0, SIZE, 28);

        int y = 170;
        g.setColor(MUTED);
        g.setFont(new Font(FONT, Font.PLAIN, 34));
        g.drawString(kicker.toUpperCase(), 80, y);

        y += 120;
        g.setColor(INK);
        g.setFont(new Font(FONT, Font.BOLD, 96));
        g.drawString(String.valueOf(vacancies.size()), 80, y);
        int numberWidth = g.getFontMetrics().stringWidth(String.valueOf(vacancies.size()));
        g.setFont(new Font(FONT, Font.BOLD, 52));
        g.drawString(vacanciesWord(vacancies.size()), 80 + numberWidth + 24, y);

        y += 60;
        g.setFont(new Font(FONT, Font.PLAIN, 40));
        int shown = 0;
        for (Vacancy v : vacancies) {
            if (shown == DIGEST_CARD_LINES || y > SIZE - 230) break;
            y += 70;
            g.setColor(accent);
            g.fillOval(80, y - 26, 16, 16);
            g.setColor(INK);
            // одна строка на вакансию: длинное название обрезается многоточием
            List<String> line = wrap(safe(v.getTitle(), "Вакансия"), g.getFontMetrics(), SIZE - 200, 1);
            if (!line.isEmpty()) g.drawString(line.get(0), 116, y);
            shown++;
        }
        if (vacancies.size() > shown) {
            y += 70;
            g.setColor(MUTED);
            g.drawString("и ещё " + (vacancies.size() - shown) + " — в посте", 116, y);
        }

        footer(g, communityName);
        g.dispose();
        return png(img);
    }

    /** Сколько названий помещается на карточке подборки, дальше — «и ещё N». */
    private static final int DIGEST_CARD_LINES = 6;

    private static String vacanciesWord(int n) {
        int mod100 = n % 100, mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 14) return "вакансий";
        if (mod10 == 1) return "вакансия";
        if (mod10 >= 2 && mod10 <= 4) return "вакансии";
        return "вакансий";
    }

    private static void setup(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private static void footer(Graphics2D g, String communityName) {
        g.setColor(MUTED);
        g.setStroke(new BasicStroke(3));
        g.drawLine(80, SIZE - 150, SIZE - 80, SIZE - 150);
        g.setFont(new Font(FONT, Font.PLAIN, 36));
        g.drawString(communityName, 80, SIZE - 85);
    }

    static Color accentFor(String novelty) {
        if (novelty == null) return new Color(0x3B, 0x82, 0xF6);
        return switch (novelty) {
            case "green" -> new Color(0x22, 0xA0, 0x6B);
            case "red" -> new Color(0xD9, 0x48, 0x3B);
            case "yellow" -> new Color(0xE0, 0xA3, 0x00);
            default -> new Color(0x3B, 0x82, 0xF6);
        };
    }

    /**
     * Печатает текст с переносом по словам в ширину, не больше maxLines строк (последняя
     * обрезается с «…»). Возвращает y после последней строки.
     */
    static int drawWrapped(Graphics2D g, String text, int x, int y, int width, int maxLines) {
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = wrap(text, fm, width, maxLines);
        int lineHeight = fm.getHeight();
        for (String line : lines) {
            g.drawString(line, x, y);
            y += lineHeight;
        }
        return y - lineHeight + fm.getDescent();
    }

    static List<String> wrap(String text, FontMetrics fm, int width, int maxLines) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String candidate = cur.isEmpty() ? word : cur + " " + word;
            if (fm.stringWidth(candidate) <= width) {
                cur.setLength(0);
                cur.append(candidate);
            } else {
                if (!cur.isEmpty()) lines.add(cur.toString());
                cur.setLength(0);
                cur.append(word);
                if (lines.size() == maxLines) break;
            }
        }
        if (!cur.isEmpty() && lines.size() < maxLines) lines.add(cur.toString());
        if (lines.size() > maxLines) lines = lines.subList(0, maxLines);
        // если текст не влез — последняя строка с многоточием
        String joined = String.join(" ", lines);
        if (!joined.equals(text.trim().replaceAll("\\s+", " ")) && !lines.isEmpty()) {
            int last = lines.size() - 1;
            String l = lines.get(last);
            while (!l.isEmpty() && fm.stringWidth(l + "…") > width) l = l.substring(0, l.length() - 1);
            lines.set(last, l.trim() + "…");
        }
        // Одно слово шире строки (длинный URL, «Специалист/координатор/ассистент» без
        // пробелов) раньше просто вылезало за край карточки — режем его по символам.
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (fm.stringWidth(l) <= width) continue;
            while (l.length() > 1 && fm.stringWidth(l + "…") > width) l = l.substring(0, l.length() - 1);
            lines.set(i, l + "…");
        }
        return lines;
    }

    private static String safe(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
