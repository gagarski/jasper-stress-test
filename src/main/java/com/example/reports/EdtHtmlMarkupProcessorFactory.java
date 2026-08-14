package com.example.reports;

import net.sf.jasperreports.engine.util.HtmlEditorKitMarkupProcessor;
import net.sf.jasperreports.engine.util.MarkupProcessor;
import net.sf.jasperreports.engine.util.MarkupProcessorFactory;

/**
 * Serializes JasperReports HTML markup conversion on the Swing EDT.
 */
public final class EdtHtmlMarkupProcessorFactory implements MarkupProcessorFactory {

    @Override
    public MarkupProcessor createMarkupProcessor() {
        return new EdtMarkupProcessor(new HtmlEditorKitMarkupProcessor());
    }
}
