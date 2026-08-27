package com.example.reports;

import net.sf.jasperreports.engine.util.JRStyledText;
import net.sf.jasperreports.engine.util.JRStyledTextParser;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.font.TextAttribute;
import java.text.AttributedCharacterIterator.Attribute;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JsoupHtmlMarkupProcessorTest {

    @Test
    void turnsComputedCssIntoJasperStyledTextAttributes() throws Exception {
        String converted = new JsoupHtmlMarkupProcessor().convert("""
                <style>
                    .message { color: hsl(210, 50%, 40%); font-size: 10pt; }
                    .message strong { font-size: 150%; text-decoration: underline; }
                </style>
                <span class="message"><strong style="font-weight: 700">text</strong></span>
                """);

        JRStyledText styledText = JRStyledTextParser.getInstance().parse(
                Map.of(), converted, Locale.ROOT
        );
        Map<Attribute, Object> attributes = styledText.getRuns().get(0).attributes;

        assertEquals("text", styledText.getText());
        assertEquals(new Color(0x336699), attributes.get(TextAttribute.FOREGROUND));
        assertEquals(15f, attributes.get(TextAttribute.SIZE));
        assertEquals(TextAttribute.WEIGHT_BOLD, attributes.get(TextAttribute.WEIGHT));
        assertEquals(TextAttribute.UNDERLINE_ON, attributes.get(TextAttribute.UNDERLINE));
    }

    @Test
    void flattensAncestorVisualStylesOntoDescendantText() throws Exception {
        String converted = new JsoupHtmlMarkupProcessor().convert("""
                <span style="color: blue; background-color: yellow">
                    <i style="font-style: unset">text</i>
                </span>
                """);

        JRStyledText styledText = JRStyledTextParser.getInstance().parse(
                Map.of(), converted, Locale.ROOT
        );
        Map<Attribute, Object> attributes = styledText.getRuns().get(0).attributes;

        assertEquals(Color.BLUE, attributes.get(TextAttribute.FOREGROUND));
        assertEquals(Color.YELLOW, attributes.get(TextAttribute.BACKGROUND));
        assertFalse(attributes.containsKey(TextAttribute.POSTURE));
    }

    @Test
    void parsesAndResolvesStylesSafelyUnderConcurrentUse() throws Exception {
        String html = """
                <style>
                    span.message { color: hsl(210, 50%, 40%); font-size: 10pt; }
                    #target { font-weight: bold; }
                </style>
                <span id="target" class="message"
                      style="font-size: 125%; background: rgba(10, 20, 30, .5)">
                    concurrent text
                </span>
                """;
        JsoupHtmlMarkupProcessor processor = new JsoupHtmlMarkupProcessor();
        String reference = processor.convert(html);
        List<Callable<String>> conversions = IntStream.range(0, 500)
                .mapToObj(index -> (Callable<String>) () -> processor.convert(html))
                .toList();

        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            for (Future<String> conversion : executor.invokeAll(conversions)) {
                assertEquals(reference, conversion.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
