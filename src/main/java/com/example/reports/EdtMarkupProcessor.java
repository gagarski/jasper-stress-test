package com.example.reports;

import net.sf.jasperreports.engine.util.MarkupProcessor;

import javax.swing.SwingUtilities;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * Decorates a markup processor by running all conversions on the Swing EDT.
 */
final class EdtMarkupProcessor implements MarkupProcessor {

    private final MarkupProcessor delegate;

    EdtMarkupProcessor(MarkupProcessor delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public String convert(String source) {
        if (SwingUtilities.isEventDispatchThread()) {
            return delegate.convert(source);
        }

        FutureTask<String> conversion = new FutureTask<>(() -> delegate.convert(source));
        SwingUtilities.invokeLater(conversion);

        try {
            return conversion.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for HTML conversion on the EDT",
                    exception
            );
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("HTML conversion on the EDT failed", cause);
        }
    }
}
