package com.example.reports;

import com.example.reports.HtmlPrintVerifier.HtmlMismatch;
import com.example.reports.HtmlPrintVerifier.VerificationResult;
import me.tongfei.progressbar.ConsoleProgressBarConsumer;
import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.JREmptyDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.SimpleJasperReportsContext;
import org.kohsuke.args4j.CmdLineException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class JasperStressTestApplication {

    private static final String MAIN_REPORT_RESOURCE = "reports/main_report.jrxml";
    private static final int PROGRESS_BAR_WIDTH = 100;

    private JasperStressTestApplication() {
    }

    public static void main(String[] args) throws Exception {
        StressTestOptions options;
        try {
            options = StressTestOptions.parse(args);
        } catch (CmdLineException exception) {
            System.err.println("Error: " + exception.getMessage());
            System.err.println();
            StressTestOptions.printUsage(System.err);
            return;
        }
        if (options.help()) {
            StressTestOptions.printUsage(System.out);
            return;
        }

        if (!options.generateGolden()) {
            if (options.htmlEditorKitWarmup()) {
                JasperSwingHacks.warmUpHtmlEditorKit();
            }
            if (options.jEditorPaneWarmup()) {
                JasperSwingHacks.warmUpJEditorPane();
            }
        }

        SimpleJasperReportsContext context = createContext(
                !options.generateGolden() && options.legacyJEditorPaneProcessor(),
                !options.generateGolden() && options.css4jHtmlProcessor(),
                !options.generateGolden() && options.flyingSaucerHtmlProcessor(),
                options.generateGolden() || options.edtRendering()
        );
        JasperReport mainReport = compileMainReport(context);
        JasperFillManager fillManager = JasperFillManager.getInstance(context);

        if (options.generateGolden()) {
            generateGoldenReference(
                    context,
                    fillManager,
                    mainReport,
                    options.goldenOutput()
            );
            return;
        }

        HtmlPrintVerifier verifier = HtmlPrintVerifier.fromResource(
                context,
                JasperStressTestApplication.class.getClassLoader()
        );

        AtomicLong successfulRuns = new AtomicLong();
        AtomicLong mismatchRuns = new AtomicLong();
        AtomicLong erroredRuns = new AtomicLong();
        AtomicReference<HtmlMismatch> firstMismatch = new AtomicReference<>();
        AtomicReference<Throwable> firstError = new AtomicReference<>();

        long startedAt = System.nanoTime();
        runTasks(
                options.tasks(),
                options.threads(),
                () -> executeRun(
                        fillManager,
                        mainReport,
                        verifier,
                        successfulRuns,
                        mismatchRuns,
                        erroredRuns,
                        firstMismatch,
                        firstError
                ),
                erroredRuns,
                firstError
        );
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        printResults(
                options,
                elapsed,
                successfulRuns.get(),
                mismatchRuns.get(),
                erroredRuns.get(),
                firstMismatch.get(),
                firstError.get(),
                verifier
        );

        if (mismatchRuns.get() != 0 || erroredRuns.get() != 0) {
            throw new IllegalStateException("Stress test completed with failures");
        }
    }

    private static void generateGoldenReference(
            SimpleJasperReportsContext context,
            JasperFillManager fillManager,
            JasperReport mainReport,
            Path output
    ) throws Exception {
        JasperPrint print = fillManager.fill(
                mainReport,
                new HashMap<>(),
                new JREmptyDataSource(1)
        );
        HtmlPrintVerifier referenceGenerator = HtmlPrintVerifier.forGeneration(context);
        HtmlPrintVerifier.HtmlPrintReference reference =
                referenceGenerator.writeReference(print, output);

        System.out.printf(
                "Golden reference written: %s (%d HTML element(s))%n",
                output.toAbsolutePath().normalize(),
                reference.htmlTextSnapshots().size()
        );
        System.out.println("Renderer: modern HtmlEditorKitMarkupProcessor on EDT");
    }

    private static SimpleJasperReportsContext createContext(
            boolean legacyJEditorPaneProcessor,
            boolean css4jHtmlProcessor,
            boolean flyingSaucerHtmlProcessor,
            boolean edtRendering
    ) {
        SimpleJasperReportsContext context = new SimpleJasperReportsContext(
                DefaultJasperReportsContext.getInstance()
        );
        if (edtRendering) {
            JasperSwingHacks.enableEdtHtmlRendering(
                    context,
                    legacyJEditorPaneProcessor,
                    css4jHtmlProcessor,
                    flyingSaucerHtmlProcessor
            );
        } else if (legacyJEditorPaneProcessor) {
            JasperSwingHacks.useLegacyJEditorPaneHtmlProcessor(context);
        } else if (css4jHtmlProcessor) {
            JasperSwingHacks.useCss4jHtmlProcessor(context);
        } else if (flyingSaucerHtmlProcessor) {
            JasperSwingHacks.useFlyingSaucerHtmlProcessor(context);
        }

        return context;
    }

    private static JasperReport compileMainReport(SimpleJasperReportsContext context)
            throws JRException, IOException {
        try (InputStream input = JasperStressTestApplication.class.getClassLoader()
                .getResourceAsStream(MAIN_REPORT_RESOURCE)) {
            if (input == null) {
                throw new IOException(
                        "Main report resource not found: " + MAIN_REPORT_RESOURCE
                );
            }
            return JasperCompileManager.getInstance(context).compile(input);
        }
    }

    private static void executeRun(
            JasperFillManager fillManager,
            JasperReport mainReport,
            HtmlPrintVerifier verifier,
            AtomicLong successfulRuns,
            AtomicLong mismatchRuns,
            AtomicLong erroredRuns,
            AtomicReference<HtmlMismatch> firstMismatch,
            AtomicReference<Throwable> firstError
    ) {
        try {
            JasperPrint print = fillManager.fill(
                    mainReport,
                    new HashMap<>(),
                    new JREmptyDataSource(1)
            );
            VerificationResult result = verifier.verify(print);
            if (result.matches()) {
                successfulRuns.incrementAndGet();
            } else {
                mismatchRuns.incrementAndGet();
                firstMismatch.compareAndSet(null, result.mismatch());
            }
        } catch (Exception exception) {
            erroredRuns.incrementAndGet();
            firstError.compareAndSet(null, exception);
        }
    }

    private static void runTasks(
            long runCount,
            int threadCount,
            Runnable task,
            AtomicLong erroredRuns,
            AtomicReference<Throwable> firstError
    ) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(
                threadCount,
                new StressThreadFactory()
        );
        CompletionService<Void> completions = new ExecutorCompletionService<>(executor);
        int maximumInFlight = Math.max(threadCount, threadCount * 4);
        long submitted = 0;
        long completed = 0;
        int inFlight = 0;
        boolean completedAllTasks = false;

        try {
            try (ProgressBar progressBar = new ProgressBarBuilder()
                    .setTaskName("Report fills")
                    .setInitialMax(runCount)
                    .setUpdateIntervalMillis(250)
                    .setConsumer(new ConsoleProgressBarConsumer(
                            System.out,
                            PROGRESS_BAR_WIDTH
                    ))
                    .showSpeed(new DecimalFormat("0.0"))
                    .build()) {
                while (completed < runCount) {
                    while (submitted < runCount && inFlight < maximumInFlight) {
                        completions.submit(() -> {
                            task.run();
                            return null;
                        });
                        submitted++;
                        inFlight++;
                    }

                    Future<Void> completedTask = completions.take();
                    completed++;
                    inFlight--;
                    progressBar.step();
                    try {
                        completedTask.get();
                    } catch (ExecutionException exception) {
                        erroredRuns.incrementAndGet();
                        firstError.compareAndSet(null, exception.getCause());
                    }
                }
            }
            completedAllTasks = true;
        } finally {
            if (completedAllTasks) {
                executor.shutdown();
            } else {
                executor.shutdownNow();
            }
            executor.awaitTermination(1, TimeUnit.MINUTES);
        }
    }

    private static void printResults(
            StressTestOptions options,
            Duration elapsed,
            long successfulRuns,
            long mismatchRuns,
            long erroredRuns,
            HtmlMismatch firstMismatch,
            Throwable firstError,
            HtmlPrintVerifier verifier
    ) {
        double seconds = elapsed.toNanos() / 1_000_000_000.0;
        double throughput = seconds == 0.0 ? 0.0 : options.tasks() / seconds;

        System.out.println();
        System.out.println("JasperReports stress-test results");
        System.out.printf("Requested runs:          %,d%n", options.tasks());
        System.out.printf("Available processors:    %,d%n", options.availableProcessors());
        System.out.printf("Executor threads:        %,d%n", options.threads());
        System.out.printf(
                "HTMLEditorKit warm-up:    %s%n",
                enabled(options.htmlEditorKitWarmup())
        );
        System.out.printf(
                "JEditorPane warm-up:      %s%n",
                enabled(options.jEditorPaneWarmup())
        );
        System.out.printf(
                "Legacy HTML processor:    %s%n",
                enabled(options.legacyJEditorPaneProcessor())
        );
        System.out.printf(
                "CSS4J HTML processor:      %s%n",
                enabled(options.css4jHtmlProcessor())
        );
        System.out.printf(
                "Flying Saucer processor:   %s%n",
                enabled(options.flyingSaucerHtmlProcessor())
        );
        System.out.printf("EDT HTML rendering:      %s%n", enabled(options.edtRendering()));
        System.out.printf("Successful runs:         %,d%n", successfulRuns);
        System.out.printf("Verification mismatches: %,d%n", mismatchRuns);
        System.out.printf("Errored runs:            %,d%n", erroredRuns);
        System.out.printf("Elapsed:                 %.3f s%n", seconds);
        System.out.printf("Throughput:              %,.1f fills/s%n", throughput);

        if (firstMismatch != null) {
            System.out.println("First mismatch - expected JSON:");
            System.out.println(verifier.toPrettyJson(firstMismatch.expected()));
            System.out.println("First mismatch - actual JSON:");
            System.out.println(verifier.toPrettyJson(firstMismatch.actual()));
        }
        if (firstError != null) {
            System.out.println("First error:");
            firstError.printStackTrace(System.out);
        }
    }

    private static String enabled(boolean value) {
        return value ? "enabled" : "disabled";
    }
}
