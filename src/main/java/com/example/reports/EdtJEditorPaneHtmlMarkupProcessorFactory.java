package com.example.reports;

import net.sf.jasperreports.engine.util.JEditorPaneHtmlMarkupProcessor;
import net.sf.jasperreports.engine.util.MarkupProcessor;
import net.sf.jasperreports.engine.util.MarkupProcessorFactory;

/**
 * Runs the legacy JEditorPane HTML markup processor on the Swing EDT.
 */
@SuppressWarnings("deprecation")
public final class EdtJEditorPaneHtmlMarkupProcessorFactory
        implements MarkupProcessorFactory {

    @Override
    public MarkupProcessor createMarkupProcessor() {
        return new EdtMarkupProcessor(
                new JEditorPaneHtmlMarkupProcessor()
        );
    }
}
