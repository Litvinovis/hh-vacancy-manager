package com.hh.gui.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract test against a byte-for-byte realistic CBR daily-rates response — real
 * windows-1251 bytes, real "encoding=windows-1251" XML prolog, comma decimals, the
 * NumCode/Name/VunitRate fields we don't use — not a fixture we invented ourselves.
 * The point is to catch a real feed schema change (renamed/reordered elements, a
 * dropped Nominal, a switched decimal separator) rather than just re-testing our own
 * parser against data shaped exactly how the parser expects it.
 */
class CurrencyRateServiceTest {

    private static final Charset WIN1251 = Charset.forName("windows-1251");

    // A trimmed but structurally real CBR_daily.asp response (see cbr.ru/scripts/XML_daily.asp):
    // three Valute entries, one with Nominal != 1 (CNY, quoted per 10 units) to exercise
    // the nominal-division path, plus a genuinely Cyrillic Name field per entry to prove
    // the windows-1251 declaration round-trips even though Name itself is never read.
    private static final String CBR_SAMPLE_XML =
        "<?xml version=\"1.0\" encoding=\"windows-1251\"?>\n" +
        "<ValCurs Date=\"20.08.2026\" name=\"Foreign Currency Market\">\n" +
        "<Valute ID=\"R01235\">\n" +
        "<NumCode>840</NumCode>\n" +
        "<CharCode>USD</CharCode>\n" +
        "<Nominal>1</Nominal>\n" +
        "<Name>Доллар США</Name>\n" +
        "<Value>92,4531</Value>\n" +
        "<VunitRate>92,4531</VunitRate>\n" +
        "</Valute>\n" +
        "<Valute ID=\"R01239\">\n" +
        "<NumCode>978</NumCode>\n" +
        "<CharCode>EUR</CharCode>\n" +
        "<Nominal>1</Nominal>\n" +
        "<Name>Евро</Name>\n" +
        "<Value>100,1234</Value>\n" +
        "<VunitRate>100,1234</VunitRate>\n" +
        "</Valute>\n" +
        "<Valute ID=\"R01375\">\n" +
        "<NumCode>156</NumCode>\n" +
        "<CharCode>CNY</CharCode>\n" +
        "<Nominal>10</Nominal>\n" +
        "<Name>Китайских юаней</Name>\n" +
        "<Value>128,7650</Value>\n" +
        "<VunitRate>12,87650</VunitRate>\n" +
        "</Valute>\n" +
        "</ValCurs>";

    private HttpServer server;
    private CurrencyRateService service;
    private volatile String responseXml = CBR_SAMPLE_XML;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/scripts/XML_daily.asp", ex -> {
            byte[] body = responseXml.getBytes(WIN1251);
            ex.getResponseHeaders().add("Content-Type", "application/xml; charset=windows-1251");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        service = new CurrencyRateService();
        ReflectionTestUtils.setField(service, "cbrDailyRatesUrl",
            "http://127.0.0.1:" + port + "/scripts/XML_daily.asp");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void refresh_realisticCbrFeed_parsesRatesCorrectly() {
        service.refresh();

        assertEquals(92.4531, service.rubPerUnit("USD"), 0.0001);
        assertEquals(100.1234, service.rubPerUnit("EUR"), 0.0001);
        // Nominal=10 — rubPerUnit divides by nominal, not the raw <Value>.
        assertEquals(12.8765, service.rubPerUnit("CNY"), 0.0001);
    }

    @Test
    void refresh_lowercaseCurrencyCodeLookup_stillMatches() {
        service.refresh();
        assertEquals(92.4531, service.rubPerUnit("usd"), 0.0001);
    }

    @Test
    void refresh_unknownCurrency_returnsNull() {
        service.refresh();
        assertNull(service.rubPerUnit("XYZ"));
    }

    @Test
    void rubPerUnit_rurAndRub_alwaysOneWithoutNeedingFeed() {
        assertEquals(1.0, service.rubPerUnit("RUR"));
        assertEquals(1.0, service.rubPerUnit("RUB"));
    }

    @Test
    void refresh_entryMissingValue_skippedWithoutAbortingRestOfFeed() {
        responseXml =
            "<?xml version=\"1.0\" encoding=\"windows-1251\"?>\n" +
            "<ValCurs Date=\"20.08.2026\" name=\"Foreign Currency Market\">\n" +
            "<Valute ID=\"R01235\">\n" +
            "<NumCode>840</NumCode>\n" +
            "<CharCode>USD</CharCode>\n" +
            "<Nominal>1</Nominal>\n" +
            "<Name>Доллар США</Name>\n" +
            "</Valute>\n" + // no <Value> — malformed entry
            "<Valute ID=\"R01239\">\n" +
            "<NumCode>978</NumCode>\n" +
            "<CharCode>EUR</CharCode>\n" +
            "<Nominal>1</Nominal>\n" +
            "<Name>Евро</Name>\n" +
            "<Value>100,1234</Value>\n" +
            "</Valute>\n" +
            "</ValCurs>";

        service.refresh();

        assertNull(service.rubPerUnit("USD"), "запись без Value должна быть пропущена");
        assertEquals(100.1234, service.rubPerUnit("EUR"), 0.0001, "остальной фид не должен пострадать");
    }

    @Test
    void refresh_feedUnreachable_keepsPreviousCache() {
        service.refresh(); // seed cache from the real sample first
        assertEquals(92.4531, service.rubPerUnit("USD"), 0.0001);

        server.stop(0); // now make the feed unreachable

        service.refresh();

        assertEquals(92.4531, service.rubPerUnit("USD"), 0.0001, "неудачный рефреш не должен стирать прошлый кэш");
    }
}
