package com.example.reports;

import net.sf.jasperreports.engine.JRCommonText;
import net.sf.jasperreports.engine.SimpleJasperReportsContext;
import net.sf.jasperreports.engine.util.JEditorPaneMarkupProcessor;
import net.sf.jasperreports.engine.util.MarkupProcessorFactory;

import javax.swing.JEditorPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.html.HTMLEditorKit;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;

/**
 * Opt-in workarounds for Swing HTML initialization and concurrent JasperReports fills.
 */
public final class JasperSwingHacks {

    private static final String HTML_WARMUP = """
            <html><body>
            <b>bold</b> plain <i>italic</i> <u>underline</u>
            <font color="#2B6CB0">color</font><br><br>
            <b><i><font color="#9C4221">nested</font></i></b>
            H<sub>2</sub>O, mc<sup>2</sup>, <s>strike</s>, <b>bold</b><br>
            <font size="14">large</font>
            <font size="8" color="#718096">small</font>
            <b><font color="#276749">status</font></b><br><br>
            <i>italic paragraph</i> and <u>underlined text</u>
            </body></html>
            """;

    private JasperSwingHacks() {
    }

    public static void warmUpHtmlEditorKit() {
        runOnEdt(JasperSwingHacks::parseWarmupHtml, "HTMLEditorKit warm-up");
    }

    public static void warmUpJEditorPane() {
        runOnEdt(() -> {
            JEditorPane pane = new JEditorPane("text/html", HTML_WARMUP);
            pane.setEditable(false);
        }, "JEditorPane warm-up");
    }

    public static void useLegacyJEditorPaneHtmlProcessor(
            SimpleJasperReportsContext context
    ) {
        setHtmlProcessorFactory(
                context,
                JEditorPaneMarkupProcessor.HtmlFactory.class
        );
    }

    public static void useCss4jHtmlProcessor(SimpleJasperReportsContext context) {
        setHtmlProcessorFactory(
                context,
                Css4jHtmlMarkupProcessor.Factory.class
        );
    }

    public static void useFlyingSaucerHtmlProcessor(
            SimpleJasperReportsContext context
    ) {
        setHtmlProcessorFactory(
                context,
                FlyingSaucerHtmlMarkupProcessor.Factory.class
        );
    }

    public static void useJsoupHtmlProcessor(SimpleJasperReportsContext context) {
        setHtmlProcessorFactory(
                context,
                JsoupHtmlMarkupProcessor.Factory.class
        );
    }

    public static void enableEdtHtmlRendering(
            SimpleJasperReportsContext context,
            boolean legacyJEditorPaneProcessor,
            boolean css4jHtmlProcessor,
            boolean flyingSaucerHtmlProcessor,
            boolean jsoupHtmlProcessor
    ) {
        setHtmlProcessorFactory(
                context,
                edtFactory(
                        legacyJEditorPaneProcessor,
                        css4jHtmlProcessor,
                        flyingSaucerHtmlProcessor,
                        jsoupHtmlProcessor
                )
        );
    }

    private static Class<? extends MarkupProcessorFactory> edtFactory(
            boolean legacyJEditorPaneProcessor,
            boolean css4jHtmlProcessor,
            boolean flyingSaucerHtmlProcessor,
            boolean jsoupHtmlProcessor
    ) {
        if (legacyJEditorPaneProcessor) {
            return EdtJEditorPaneHtmlMarkupProcessorFactory.class;
        }
        if (css4jHtmlProcessor) {
            return EdtCss4jHtmlMarkupProcessorFactory.class;
        }
        if (flyingSaucerHtmlProcessor) {
            return EdtFlyingSaucerHtmlMarkupProcessorFactory.class;
        }
        if (jsoupHtmlProcessor) {
            return EdtJsoupHtmlMarkupProcessorFactory.class;
        }
        return EdtHtmlMarkupProcessorFactory.class;
    }

    private static void setHtmlProcessorFactory(
            SimpleJasperReportsContext context,
            Class<? extends MarkupProcessorFactory> factoryClass
    ) {
        context.setProperty(
                MarkupProcessorFactory.PROPERTY_MARKUP_PROCESSOR_FACTORY_PREFIX
                        + JRCommonText.MARKUP_HTML,
                factoryClass.getName()
        );
    }

    private static void runOnEdt(Runnable action, String description) {
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during " + description, exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(description + " failed", cause);
        }
    }

    private static void parseWarmupHtml() {
        HTMLEditorKit kit = new HTMLEditorKit();
        Document document = kit.createDefaultDocument();
        try {
            kit.read(new StringReader(HTML_WARMUP), document, 0);
        } catch (IOException | BadLocationException exception) {
            throw new IllegalStateException("Could not parse Swing HTML warm-up document", exception);
        }
    }
}
