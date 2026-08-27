package com.example.reports;

import net.sf.jasperreports.engine.util.MarkupProcessor;
import net.sf.jasperreports.engine.util.MarkupProcessorFactory;

/** Runs the Jsoup-backed processor on the Swing EDT for comparison purposes. */
public final class EdtJsoupHtmlMarkupProcessorFactory
        implements MarkupProcessorFactory {

    @Override
    public MarkupProcessor createMarkupProcessor() {
        return new EdtMarkupProcessor(new JsoupHtmlMarkupProcessor());
    }
}
