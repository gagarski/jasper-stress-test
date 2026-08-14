package com.example.reports;

import net.sf.jasperreports.engine.util.MarkupProcessor;
import net.sf.jasperreports.engine.util.MarkupProcessorFactory;

/**
 * Creates Flying Saucer-backed processors whose conversions run on the EDT.
 */
public final class EdtFlyingSaucerHtmlMarkupProcessorFactory
        implements MarkupProcessorFactory {

    @Override
    public MarkupProcessor createMarkupProcessor() {
        return new EdtMarkupProcessor(new FlyingSaucerHtmlMarkupProcessor());
    }
}
