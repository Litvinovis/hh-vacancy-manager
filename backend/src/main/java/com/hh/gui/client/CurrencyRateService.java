package com.hh.gui.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RUB-per-unit exchange rates, refreshed once a day from the Central Bank of Russia's
 * free daily rates feed (no API key needed — same "free, no signup" pattern as
 * FreeModelUpdater's OpenRouter free-model list).
 *
 * Never blocks or fails formatting: a failed refresh just keeps yesterday's cached
 * rates (or, before the very first successful refresh, returns null — callers treat
 * that as "no conversion available" and print the original figure alone, same as a
 * currency this feed doesn't carry).
 */
@Component
public class CurrencyRateService {

    private static final Logger log = LoggerFactory.getLogger(CurrencyRateService.class);
    // Not final — tests point this at a local HttpServer serving a real CBR-shaped
    // response, to catch a feed schema change instead of just testing our own parser
    // against fixtures we made up (see CurrencyRateServiceTest).
    private String cbrDailyRatesUrl = "https://www.cbr.ru/scripts/XML_daily.asp";

    private final Map<String, Double> rubPerUnit = new ConcurrentHashMap<>();

    /** Fetches the CBR's current day rates and replaces the cache — called once a day
     *  by PipelineScheduler. Individual currency entries are merged in, not a full
     *  cache wipe-then-fill, so a malformed single entry can't blank out the rest. */
    public void refresh() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder builder = factory.newDocumentBuilder();

            HttpURLConnection conn = (HttpURLConnection) new URL(cbrDailyRatesUrl).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            Document doc = builder.parse(conn.getInputStream());

            NodeList valutes = doc.getElementsByTagName("Valute");
            int updated = 0;
            for (int i = 0; i < valutes.getLength(); i++) {
                Element el = (Element) valutes.item(i);
                String code = childText(el, "CharCode");
                String nominalStr = childText(el, "Nominal");
                String valueStr = childText(el, "Value");
                if (code == null || nominalStr == null || valueStr == null) continue;
                try {
                    double nominal = Double.parseDouble(nominalStr);
                    // CBR uses a comma decimal separator ("92,4531"), not a dot.
                    double value = Double.parseDouble(valueStr.replace(',', '.'));
                    if (nominal <= 0) continue;
                    rubPerUnit.put(code.toUpperCase(), value / nominal);
                    updated++;
                } catch (NumberFormatException ignored) {
                    // One malformed entry shouldn't abort the rest of the feed.
                }
            }
            log.info("Курсы валют ЦБ РФ обновлены: {} валют", updated);
        } catch (Exception e) {
            log.warn("Не удалось обновить курсы валют ЦБ РФ, используем прошлый кэш: {}", e.getMessage());
        }
    }

    /**
     * RUB per one unit of {@code currencyCode}, or null if unknown / not yet loaded
     * (fail-open — the caller just omits the converted figure). RUR/RUB are always 1:1
     * without needing the feed, since that's this app's own reporting currency.
     */
    public Double rubPerUnit(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) return null;
        String code = currencyCode.toUpperCase();
        if ("RUR".equals(code) || "RUB".equals(code)) return 1.0;
        return rubPerUnit.get(code);
    }

    private static String childText(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return null;
        return nodes.item(0).getTextContent();
    }
}
