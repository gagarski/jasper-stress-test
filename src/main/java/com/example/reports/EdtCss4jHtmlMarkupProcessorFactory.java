package com.example.reports;

import net.sf.jasperreports.engine.util.MarkupProcessor;
import net.sf.jasperreports.engine.util.MarkupProcessorFactory;

/**
 * Creates CSS4J-backed HTML processors whose conversions run on the Swing EDT.
 */
public final class EdtCss4jHtmlMarkupProcessorFactory
        implements MarkupProcessorFactory {

    @Override
    public MarkupProcessor createMarkupProcessor() {
        return new EdtMarkupProcessor(new Css4jHtmlMarkupProcessor());
    }
}
