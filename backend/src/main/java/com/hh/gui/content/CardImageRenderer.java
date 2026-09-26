package com.hh.gui.content;

import com.hh.gui.model.Vacancy;
import com.hh.gui.util.SalaryFormatter;
import com.hh.gui.util.VkPostFormatter;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Карточка-картинка к посту VK, 1080×1080, рисуется здесь же (Java2D, headless).
 *
 * Зачем своя, а не стоковая: пост с картинкой в умной ленте ВК стабильно обгоняет голый
 * текст, а стоковые фото к вакансиям выглядят чужими и требуют внешнего API с ключом.
 *
 * Переработана 26.09.2026: все карточки были одним белым шаблоном с полосой сверху и
 * шрифтом DejaVu Sans — лента выглядела как один и тот же пост. Теперь:
 * <ul>
 *   <li>шрифт Manrope (OFL) из ресурсов приложения — не зависит от шрифтов сервера;
 *       если не загрузился, остаётся DejaVu Sans, и карточка всё равно рисуется;</li>
 *   <li>восемь цветовых тем и три вёрстки вакансии; выбор детерминирован по самой
 *       вакансии — соседние посты разные, а повторная отрисовка той же вакансии совпадает.</li>
 * </ul>
 */
public final class CardImageRenderer {
    private CardImageRenderer() {}

    static final int SIZE = 1080;
    private static final int PAD = 88;
    private static final String FALLBACK_FONT = "DejaVu Sans";

    // ── Шрифты ──────────────────────────────────────────────────────────────

    private static final Font MEDIUM = loadFont("/fonts/Manrope-500.ttf", Font.PLAIN);
    private static final Font BOLD = loadFont("/fonts/Manrope-700.ttf", Font.BOLD);
    private static final Font EXTRA = loadFont("/fonts/Manrope-800.ttf", Font.BOLD);

    private static Font loadFont(String resource, int fallbackStyle) {
        try (InputStream in = CardImageRenderer.class.getResourceAsStream(resource)) {
            if (in != null) return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception ignored) {
            // упадём на системный шрифт ниже — картинка важнее гарнитуры
        }
        return new Font(FALLBACK_FONT, fallbackStyle, 12);
    }

    private static Font medium(float size) { return MEDIUM.deriveFont(size); }
    private static Font bold(float size) { return BOLD.deriveFont(size); }
    private static Font extra(float size) { return EXTRA.deriveFont(size); }

    // ── Темы ────────────────────────────────────────────────────────────────

    /**
     * Цветовая тема: фон (градиент), основной и приглушённый текст, акцент, плашки.
     * dark — светлый текст на тёмном/насыщенном фоне.
     */
    record Theme(String name, Color bgFrom, Color bgTo, Color ink, Color muted, Color accent,
                 Color chipBg, Color chipInk, Color blob, boolean dark) {}

    static final List<Theme> THEMES = List.of(
        new Theme("indigo", rgb(0x4F46E5), rgb(0x7C3AED), Color.WHITE, rgba(0xFFFFFF, 190), rgb(0xFDE68A),
            rgba(0xFFFFFF, 40), Color.WHITE, rgba(0xFFFFFF, 26), true),
        new Theme("ocean", rgb(0x0E7490), rgb(0x1E3A8A), Color.WHITE, rgba(0xFFFFFF, 190), rgb(0x67E8F9),
            rgba(0xFFFFFF, 38), Color.WHITE, rgba(0xFFFFFF, 22), true),
        new Theme("midnight", rgb(0x0F172A), rgb(0x1E293B), rgb(0xF8FAFC), rgb(0x94A3B8), rgb(0x34D399),
            rgb(0x1F2B40), rgb(0xE2E8F0), rgba(0x34D399, 28), true),
        new Theme("sunset", rgb(0xF97316), rgb(0xDB2777), Color.WHITE, rgba(0xFFFFFF, 200), rgb(0xFEF3C7),
            rgba(0xFFFFFF, 42), Color.WHITE, rgba(0xFFFFFF, 28), true),
        new Theme("forest", rgb(0x065F46), rgb(0x047857), Color.WHITE, rgba(0xFFFFFF, 190), rgb(0xBEF264),
            rgba(0xFFFFFF, 36), Color.WHITE, rgba(0xFFFFFF, 22), true),
        new Theme("paper", rgb(0xFAFAF7), rgb(0xF1EFE8), rgb(0x1C1917), rgb(0x78716C), rgb(0xEA580C),
            rgb(0xFFEDD5), rgb(0x9A3412), rgba(0xEA580C, 22), false),
        new Theme("mint", rgb(0xECFDF5), rgb(0xD1FAE5), rgb(0x064E3B), rgb(0x3F7A66), rgb(0x059669),
            rgb(0xFFFFFF), rgb(0x065F46), rgba(0x10B981, 30), false),
        new Theme("lilac", rgb(0xF5F3FF), rgb(0xEDE9FE), rgb(0x2E1065), rgb(0x6D5BA8), rgb(0x7C3AED),
            rgb(0xFFFFFF), rgb(0x5B21B6), rgba(0x8B5CF6, 30), false)
    );

    /** Стабильный номер для выбора темы/вёрстки: по id, иначе по названию. */
    static int seedOf(Vacancy v) {
        if (v == null) return 0;
        if (v.getId() != null) return Long.hashCode(v.getId() * 2654435761L);
        return Objects.hashCode(v.getHhId()) ^ Objects.hashCode(v.getTitle());
    }

    static Theme themeFor(int seed) {
        return THEMES.get(Math.floorMod(seed, THEMES.size()));
    }

    // ── Карточка вакансии ───────────────────────────────────────────────────

    /** Три вёрстки вакансии; выбирается по seed вместе с темой. */
    enum Layout { TITLE_FIRST, SALARY_FIRST, CENTERED }

    public static byte[] vacancyCard(Vacancy v, String communityName) throws IOException {
        int seed = seedOf(v);
        Theme t = themeFor(seed);
        Layout layout = Layout.values()[Math.floorMod(seed >>> 3, Layout.values().length)];
        return vacancyCard(v, communityName, t, layout);
    }

    static byte[] vacancyCard(Vacancy v, String communityName, Theme t, Layout layout) throws IOException {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        setup(g);
        background(g, t, seedOf(v));

        String title = safe(v != null ? v.getTitle() : null, "Вакансия");
        boolean hasSalary = v != null && SalaryFormatter.hasSalary(v);
        String salary = hasSalary ? keepNumbersTogether(SalaryFormatter.forReport(v)) : "зарплата в описании";
        String company = v != null && v.getCompany() != null && !v.getCompany().isBlank()
            && !v.getCompany().startsWith("@") ? v.getCompany().trim() : null;
        String sphere = v != null ? VkPostFormatter.kindTag(v.getTitle()) : null;

        List<String> chips = new ArrayList<>();
        chips.add("удалённо");
        if (sphere != null) chips.add(sphere.replace("#", ""));

        // Область под текст: от плашек до подвала. Блок центрируется по вертикали —
        // иначе короткое название оставляло пустой всю нижнюю половину карточки.
        int top = 150 + 40, bottom = SIZE - 170;
        switch (layout) {
            case TITLE_FIRST -> {
                chipsRow(g, t, chips, PAD, 150);
                Block block = (gg, y) -> {
                    gg.setColor(t.ink());
                    gg.setFont(extra(76));
                    y = drawWrapped(gg, title, PAD, y, SIZE - 2 * PAD, 4);
                    y += 96;
                    gg.setColor(hasSalary ? t.accent() : t.muted());
                    gg.setFont(bold(hasSalary ? 58 : 44));
                    y = drawWrapped(gg, salary, PAD, y, SIZE - 2 * PAD, 2);
                    if (company != null) {
                        y += 64;
                        gg.setColor(t.muted());
                        gg.setFont(medium(38));
                        y = drawWrapped(gg, company, PAD, y, SIZE - 2 * PAD, 2);
                    }
                    return y;
                };
                block.draw(g, centeredStart(block, top, bottom, 76));
            }
            case SALARY_FIRST -> {
                chipsRow(g, t, chips, PAD, 150);
                Block block = (gg, y) -> {
                    gg.setColor(hasSalary ? t.accent() : t.muted());
                    gg.setFont(extra(hasSalary ? 84 : 52));
                    y = drawWrapped(gg, salary, PAD, y, SIZE - 2 * PAD, 2);
                    y += 44;
                    gg.setColor(t.muted());
                    gg.setStroke(new BasicStroke(6, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    gg.drawLine(PAD, y, PAD + 120, y);
                    y += 100;
                    gg.setColor(t.ink());
                    gg.setFont(bold(62));
                    y = drawWrapped(gg, title, PAD, y, SIZE - 2 * PAD, 4);
                    if (company != null) {
                        y += 60;
                        gg.setColor(t.muted());
                        gg.setFont(medium(38));
                        y = drawWrapped(gg, company, PAD, y, SIZE - 2 * PAD, 2);
                    }
                    return y;
                };
                block.draw(g, centeredStart(block, top, bottom, hasSalary ? 84 : 52));
            }
            case CENTERED -> {
                // Панель по размеру содержимого, по центру карточки
                int panelX = 64, panelW = SIZE - 128, innerX = panelX + 64, innerW = panelW - 128;
                Block block = (gg, y) -> {
                    gg.setColor(t.muted());
                    gg.setFont(bold(32));
                    gg.drawString("ВАКАНСИЯ НА УДАЛЁНКЕ", innerX, y);
                    y += 110;
                    gg.setColor(t.ink());
                    gg.setFont(extra(68));
                    y = drawWrapped(gg, title, innerX, y, innerW, 4);
                    y += 90;
                    gg.setColor(hasSalary ? t.accent() : t.muted());
                    gg.setFont(bold(hasSalary ? 56 : 42));
                    y = drawWrapped(gg, salary, innerX, y, innerW, 2);
                    if (company != null) {
                        y += 60;
                        gg.setColor(t.muted());
                        gg.setFont(medium(36));
                        y = drawWrapped(gg, company, innerX, y, innerW, 1);
                    }
                    return y;
                };
                int contentH = measure(block) + 32;
                int panelPad = 80;
                int panelH = contentH + 2 * panelPad;
                int panelY = Math.max(90, (SIZE - 150 - panelH) / 2);
                g.setColor(t.dark() ? rgba(0x000000, 60) : rgba(0xFFFFFF, 215));
                g.fill(new RoundRectangle2D.Float(panelX, panelY, panelW, panelH, 56, 56));
                block.draw(g, panelY + panelPad + 32);
            }
        }
        footer(g, t, communityName);
        g.dispose();
        return png(img);
    }

    /** Блок текста: рисует начиная с базовой линии y, возвращает y нижнего края. */
    @FunctionalInterface
    interface Block {
        int draw(Graphics2D g, int y);
    }

    /** Высота блока: холостой проход на служебной картинке, без рисования на карточке. */
    private static int measure(Block block) {
        BufferedImage scratch = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scratch.createGraphics();
        setup(g);
        int end = block.draw(g, 0);
        g.dispose();
        return end;
    }

    /**
     * С какой базовой линии начать, чтобы блок оказался по центру между top и bottom.
     * firstLineAscent — высота первой строки над базовой линией (y блока — это базовая линия).
     */
    private static int centeredStart(Block block, int top, int bottom, int firstLineAscent) {
        int height = measure(block) + firstLineAscent;
        int start = top + Math.max(0, (bottom - top - height) / 2) + firstLineAscent;
        return Math.max(top + firstLineAscent, start);
    }

    /**
     * «от 100 000 до 120 000 ₽» переносился как «…до 120» / «000 ₽»: пробелы внутри чисел
     * и перед знаком валюты — неразрывные, перенос возможен только между словами.
     */
    static String keepNumbersTogether(String s) {
        if (s == null) return null;
        return s.replaceAll("(?<=\\d) (?=\\d)", "\u00A0")
                .replaceAll(" (?=[₽$€])", "\u00A0");
    }

    // ── Карточка статьи/опроса ──────────────────────────────────────────────

    /** Карточка статьи/опроса: тёмная тема, крупный заголовок, рубрика сверху. */
    public static byte[] articleCard(String title, String kicker, String communityName) throws IOException {
        List<Theme> darkThemes = THEMES.stream().filter(Theme::dark).toList();
        Theme t = darkThemes.get(Math.floorMod(Objects.hashCode(title), darkThemes.size()));
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        setup(g);
        background(g, t, Objects.hashCode(title));
        int y = 150;
        y = chipsRow(g, t, List.of(safe(kicker, "разбор")), PAD, y);
        y += 140;
        g.setColor(t.ink());
        g.setFont(extra(76));
        y = drawWrapped(g, safe(title, "Удалённая работа"), PAD, y, SIZE - 2 * PAD, 6);
        g.setColor(t.accent());
        g.setStroke(new BasicStroke(8, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(PAD, Math.min(y + 70, SIZE - 220), PAD + 140, Math.min(y + 70, SIZE - 220));
        footer(g, t, communityName);
        g.dispose();
        return png(img);
    }

    // ── Карточка подборки ───────────────────────────────────────────────────

    /** Сколько названий помещается на карточке подборки, дальше — «и ещё N». */
    private static final int DIGEST_CARD_LINES = 6;

    /**
     * Карточка подборки: «N вакансий» крупно и первые названия списком — чтобы по картинке
     * в ленте было видно, что внутри не одна вакансия, а выбор.
     */
    public static byte[] digestCard(List<Vacancy> vacancies, String kicker, String communityName) throws IOException {
        int seed = vacancies.isEmpty() ? 0 : seedOf(vacancies.get(0));
        Theme t = themeFor(seed);
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        setup(g);
        background(g, t, seed);

        int y = 150;
        y = chipsRow(g, t, List.of(safe(kicker, "подборка")), PAD, y);
        y += 170;
        String count = String.valueOf(vacancies.size());
        g.setColor(t.accent());
        g.setFont(extra(150));
        g.drawString(count, PAD, y);
        int numberWidth = g.getFontMetrics().stringWidth(count);
        // «7 вакансий / на удалёнку» — слово рядом с числом, уточнение строкой ниже
        g.setColor(t.ink());
        g.setFont(extra(58));
        g.drawString(vacanciesWord(vacancies.size()), PAD + numberWidth + 28, y - 58);
        g.setColor(t.muted());
        g.setFont(medium(38));
        g.drawString("на удалёнку", PAD + numberWidth + 30, y - 6);

        y += 40;
        g.setFont(bold(40));
        int shown = 0;
        for (Vacancy v : vacancies) {
            if (shown == DIGEST_CARD_LINES || y > SIZE - 250) break;
            y += 74;
            g.setColor(t.accent());
            g.fill(new RoundRectangle2D.Float(PAD, y - 30, 14, 14, 14, 14));
            g.setColor(t.ink());
            // одна строка на вакансию: длинное название обрезается многоточием
            List<String> line = wrap(safe(v.getTitle(), "Вакансия"), g.getFontMetrics(), SIZE - 2 * PAD - 40, 1);
            if (!line.isEmpty()) g.drawString(line.get(0), PAD + 40, y);
            shown++;
        }
        if (vacancies.size() > shown) {
            y += 74;
            g.setColor(t.muted());
            g.setFont(medium(38));
            g.drawString("и ещё " + (vacancies.size() - shown) + " — в посте", PAD + 40, y);
        }
        footer(g, t, communityName);
        g.dispose();
        return png(img);
    }

    private static String vacanciesWord(int n) {
        int mod100 = n % 100, mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 14) return "вакансий";
        if (mod10 == 1) return "вакансия";
        if (mod10 >= 2 && mod10 <= 4) return "вакансии";
        return "вакансий";
    }

    // ── Общие элементы ──────────────────────────────────────────────────────

    private static void setup(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    /** Градиент темы и два мягких круга-«пятна»; их положение тоже зависит от seed. */
    private static void background(Graphics2D g, Theme t, int seed) {
        boolean diagonal = (seed & 1) == 0;
        g.setPaint(diagonal
            ? new GradientPaint(0, 0, t.bgFrom(), SIZE, SIZE, t.bgTo())
            : new GradientPaint(0, 0, t.bgFrom(), 0, SIZE, t.bgTo()));
        g.fillRect(0, 0, SIZE, SIZE);
        int corner = Math.floorMod(seed >>> 5, 4);
        int bigX = (corner & 1) == 0 ? SIZE - 360 : -240;
        int bigY = (corner & 2) == 0 ? -260 : SIZE - 420;
        g.setColor(t.blob());
        g.fillOval(bigX, bigY, 620, 620);
        g.fillOval(SIZE - bigX - 380, SIZE - bigY - 300, 300, 300);
    }

    /** Ряд «плашек» (скруглённые метки). Возвращает y нижнего края ряда. */
    private static int chipsRow(Graphics2D g, Theme t, List<String> chips, int x, int y) {
        g.setFont(bold(30));
        FontMetrics fm = g.getFontMetrics();
        int h = 64;
        for (String chip : chips) {
            String text = chip.toUpperCase();
            int w = fm.stringWidth(text) + 56;
            if (x + w > SIZE - PAD) break;
            g.setColor(t.chipBg());
            g.fill(new RoundRectangle2D.Float(x, y - h, w, h, h, h));
            g.setColor(t.chipInk());
            g.drawString(text, x + 28, y - h / 2 + (fm.getAscent() - fm.getDescent()) / 2);
            x += w + 16;
        }
        return y;
    }

    private static void footer(Graphics2D g, Theme t, String communityName) {
        int y = SIZE - 96;
        // маленький знак сообщества: скруглённый квадрат акцентного цвета
        g.setColor(t.accent());
        g.fill(new RoundRectangle2D.Float(PAD, y - 34, 40, 40, 14, 14));
        g.setColor(t.muted());
        g.setFont(bold(34));
        g.drawString(safe(communityName, ""), PAD + 60, y);
    }

    /** Совместимость со старыми вызовами: цвет по оценке модели. */
    static Color accentFor(String novelty) {
        if (novelty == null) return rgb(0x3B82F6);
        return switch (novelty) {
            case "green" -> rgb(0x22A06B);
            case "red" -> rgb(0xD9483B);
            case "yellow" -> rgb(0xE0A300);
            default -> rgb(0x3B82F6);
        };
    }

    /**
     * Печатает текст с переносом по словам в ширину, не больше maxLines строк (последняя
     * обрезается с «…»). Возвращает y после последней строки.
     */
    static int drawWrapped(Graphics2D g, String text, int x, int y, int width, int maxLines) {
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = wrap(text, fm, width, maxLines);
        // Для крупных начертаний межстрочный интервал шрифта слишком свободный — чуть поджимаем
        int lineHeight = (int) Math.round(fm.getHeight() * 0.92);
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

    private static Color rgb(int hex) { return new Color(hex); }

    private static Color rgba(int hex, int alpha) {
        return new Color((hex >> 16) & 0xFF, (hex >> 8) & 0xFF, hex & 0xFF, alpha);
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
