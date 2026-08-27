package com.example.reports;

import com.helger.css.decl.CSSExpression;
import com.helger.css.property.ECSSProperty;
import com.helger.css.writer.CSSWriterSettings;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsoupCssStyleResolverTest {

    @Test
    void resolvesSpecificitySourceOrderAndInlineStyles() {
        Resolved resolved = resolve("""
                <style>
                    span { color: black; font-weight: normal; }
                    .note { color: red; font-size: 10pt; }
                    #target { color: blue; }
                    .note { font-size: 11pt; }
                </style>
                <span id="target" class="note"
                      style="color: green; font-family: 'A; B', serif">text</span>
                """);

        assertEquals("green", resolved.value(ECSSProperty.COLOR));
        assertEquals("11pt", resolved.value(ECSSProperty.FONT_SIZE));
        assertEquals("normal", resolved.value(ECSSProperty.FONT_WEIGHT));
        assertEquals("'A; B',serif", resolved.value(ECSSProperty.FONT_FAMILY));
    }

    @Test
    void importantAuthorRuleBeatsNormalInlineDeclaration() {
        Resolved resolved = resolve("""
                <style>
                    #target { color: blue !important; }
                    span { color: black !important; }
                </style>
                <span id="target" style="color: red">text</span>
                """);

        assertEquals("blue", resolved.value(ECSSProperty.COLOR));
    }

    @Test
    void importantInlineDeclarationBeatsImportantStylesheetRule() {
        Resolved resolved = resolve("""
                <style>#target { color: blue !important; }</style>
                <span id="target" style="color: red !important">text</span>
                """);

        assertEquals("red", resolved.value(ECSSProperty.COLOR));
    }

    @Test
    void expandsSupportedShorthandsBeforeApplyingTheCascade() {
        Resolved resolved = resolve("""
                <style>
                    .note {
                        font: italic 700 14pt "DejaVu Sans", sans-serif;
                        background: url(example.png) no-repeat #abc;
                        text-decoration-line: underline line-through;
                    }
                    .note { font-weight: normal; }
                </style>
                <span class="note">text</span>
                """);

        assertEquals("italic", resolved.value(ECSSProperty.FONT_STYLE));
        assertEquals("normal", resolved.value(ECSSProperty.FONT_WEIGHT));
        assertEquals("14pt", resolved.value(ECSSProperty.FONT_SIZE));
        assertEquals(
                "\"DejaVu Sans\"",
                resolved.value(ECSSProperty.FONT_FAMILY)
        );
        assertEquals("#abc", resolved.value(ECSSProperty.BACKGROUND_COLOR));
        assertEquals(
                "underline line-through",
                resolved.value(ECSSProperty.TEXT_DECORATION)
        );
    }

    @Test
    void selectorListUsesSpecificityOfTheSelectorThatMatched() {
        Resolved resolved = resolve("""
                <style>
                    #unrelated, .note { color: red; }
                    span.note { color: blue; }
                </style>
                <span class="note">text</span>
                """);

        assertEquals("blue", resolved.value(ECSSProperty.COLOR));
    }

    private static Resolved resolve(String html) {
        Document document = Jsoup.parseBodyFragment(html);
        Element target = document.selectFirst("span");
        return new Resolved(
                target,
                JsoupCssStyleResolver.from(document).resolve(target)
        );
    }

    private record Resolved(
            Element element,
            Map<ECSSProperty, CSSExpression> styles
    ) {
        private String value(ECSSProperty property) {
            CSSExpression expression = styles.get(property);
            return expression == null
                    ? null
                    : expression.getAsCSSString(new CSSWriterSettings(true), 0);
        }
    }
}
