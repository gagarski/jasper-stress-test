package com.example.reports;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;

final class StressTestOptions {
    private static final int AVAILABLE_PROCESSORS = Runtime.getRuntime().availableProcessors();
    private static final long DEFAULT_TASKS = Math.max(1, AVAILABLE_PROCESSORS * 2);

    @Option(
            name = "--tasks",
            aliases = "-n",
            metaVar = "COUNT",
            usage = "number of report fill operations"
    )
    private long tasks = DEFAULT_TASKS;

    @Option(
            name = "--threads",
            aliases = "-t",
            metaVar = "COUNT",
            usage = "number of executor threads (default: CPU x 2)"
    )
    private int threads = Math.max(1, AVAILABLE_PROCESSORS * 2);

    @Option(
            name = "--html-editor-kit-warmup",
            usage = "warm up HTMLEditorKit with a representative HTML document"
    )
    private boolean htmlEditorKitWarmup;

    @Option(
            name = "--jeditor-pane-warmup",
            usage = "warm up the complete legacy JEditorPane HTML path"
    )
    private boolean jEditorPaneWarmup;

    @Option(
            name = "--legacy-jeditor-pane-processor",
            usage = "use JasperReports' legacy JEditorPaneHtmlMarkupProcessor",
            forbids = "--css4j-html-processor"
    )
    private boolean legacyJEditorPaneProcessor;

    @Option(
            name = "--css4j-html-processor",
            usage = "use the experimental CSS4J-backed HTML markup processor",
            forbids = "--legacy-jeditor-pane-processor"
    )
    private boolean css4jHtmlProcessor;

    @Option(
            name = "--edt-rendering",
            usage = "delegate HTML rendering to the EDT"
    )
    private boolean edtRendering;

    @Option(
            name = "--generate-golden",
            metaVar = "FILE",
            usage = "fill once with the modern renderer on EDT and write golden JSON",
            forbids = {
                    "--legacy-jeditor-pane-processor",
                    "--css4j-html-processor"
            }
    )
    private File goldenOutput;

    @Option(
            name = "--help",
            aliases = "-h",
            usage = "show this help",
            help = true
    )
    private boolean help;

    static StressTestOptions parse(String[] args) throws CmdLineException {
        Objects.requireNonNull(args, "args");
        StressTestOptions options = new StressTestOptions();
        CmdLineParser parser = new CmdLineParser(options);
        parser.parseArgument(args);
        options.validate(parser);
        return options;
    }

    static void printUsage(PrintStream output) {
        output.println("Usage: mvn exec:java -Dexec.args=\"[options]\"");
        output.println();
        new CmdLineParser(new StressTestOptions()).printUsage(output);
    }

    long tasks() {
        return tasks;
    }

    int threads() {
        return threads;
    }

    int availableProcessors() {
        return AVAILABLE_PROCESSORS;
    }

    boolean htmlEditorKitWarmup() {
        return htmlEditorKitWarmup;
    }

    boolean jEditorPaneWarmup() {
        return jEditorPaneWarmup;
    }

    boolean legacyJEditorPaneProcessor() {
        return legacyJEditorPaneProcessor;
    }

    boolean css4jHtmlProcessor() {
        return css4jHtmlProcessor;
    }

    boolean edtRendering() {
        return edtRendering;
    }

    boolean generateGolden() {
        return goldenOutput != null;
    }

    Path goldenOutput() {
        return goldenOutput == null ? null : goldenOutput.toPath();
    }

    boolean help() {
        return help;
    }

    private void validate(CmdLineParser parser) throws CmdLineException {
        if (help) {
            return;
        }
        if (tasks <= 0) {
            throw new CmdLineException(parser, "--tasks must be positive");
        }
        if (threads <= 0) {
            throw new CmdLineException(parser, "--threads must be positive");
        }
    }
}
