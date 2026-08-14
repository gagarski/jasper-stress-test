# JasperReports HTML concurrency stress test

This project exercises concurrent JasperReports fills containing Swing-rendered
HTML markup. It is intended to reproduce and compare initialization and
threading behavior in JasperReports' modern `HtmlEditorKitMarkupProcessor` and
legacy `JEditorPaneHtmlMarkupProcessor` implementations.

The application:

1. Compiles a single JRXML report from the classpath.
2. Submits fill operations to a fixed-size executor.
3. Uses a non-empty `JREmptyDataSource(1)` for every fill.
4. Extracts every HTML-rendered text element from the resulting `JasperPrint`.
5. Compares its text, geometry, global attributes, and style runs with a JSON
   golden reference.
6. Reports successful fills, verification mismatches, and exceptions.

The reduced report contains three non-trivial `markup="html"` text elements and
does not use subreports or a custom JasperReports repository service.

## Requirements

- Java 17 or newer
- Maven 3.6.3 or newer

## Build

```shell
mvn clean package
```

The Spring Boot Maven plugin produces an executable archive:

```text
target/jasper-stress-test-1.0-SNAPSHOT.jar
```

Run it with:

```shell
java -jar target/jasper-stress-test-1.0-SNAPSHOT.jar [options]
```

The application can also be run through Maven:

```shell
mvn compile exec:java -Dexec.args="[options]"
```

## Command-line options

All workaround switches are opt-in. Running without switches uses the modern
HTML processor directly on executor threads, without warm-up.

| Option | Default | Description |
|---|---|---|
| `--tasks COUNT`, `-n COUNT` | `CPU × 2`, minimum 1 | Number of report fill operations submitted to the executor. |
| `--threads COUNT`, `-t COUNT` | `CPU × 2`, minimum 1 | Number of executor worker threads. |
| `--html-editor-kit-warmup` | `false` | On the Swing EDT, creates an `HTMLEditorKit`, creates its default document, and parses representative HTML before concurrent fills begin. |
| `--jeditor-pane-warmup` | `false` | On the Swing EDT, exercises the complete legacy path by constructing a `JEditorPane` with representative HTML and making it non-editable. This subsumes the editor-kit warm-up for legacy rendering. |
| `--legacy-jeditor-pane-processor` | `false` | Selects JasperReports' deprecated `JEditorPaneHtmlMarkupProcessor`. When absent, the modern `HtmlEditorKitMarkupProcessor` is used. |
| `--edt-rendering` | `false` | Delegates every HTML conversion to the Swing EDT. It can be combined with either HTML processor. |
| `--generate-golden FILE` | unset | Leaves stress-test mode, fills once using the modern processor on the EDT, and writes pretty-printed golden JSON to `FILE`. It cannot be combined with the legacy processor. |
| `--help`, `-h` | `false` | Prints command-line help and exits. |

`--tasks` and `--threads` must be positive integers. Their defaults are
calculated independently when the application starts. For example, a runtime
reporting 20 available processors defaults to 40 tasks and 40 threads.

In golden-generation mode, task count, thread count, optional warm-ups, and the
normal renderer selection are not used. The mode always performs one fill with
the modern processor delegated to the EDT.

## Useful configurations

Run the unmodified modern processor concurrently:

```shell
java -jar target/jasper-stress-test-1.0-SNAPSHOT.jar \
  --tasks 10000 \
  --threads 40
```

Warm up the modern processor before concurrent rendering:

```shell
java -jar target/jasper-stress-test-1.0-SNAPSHOT.jar \
  --tasks 10000 \
  --threads 40 \
  --html-editor-kit-warmup
```

Run the legacy processor without workarounds:

```shell
java -jar target/jasper-stress-test-1.0-SNAPSHOT.jar \
  --tasks 10000 \
  --threads 40 \
  --legacy-jeditor-pane-processor
```

Warm up the complete legacy path:

```shell
java -jar target/jasper-stress-test-1.0-SNAPSHOT.jar \
  --tasks 10000 \
  --threads 40 \
  --legacy-jeditor-pane-processor \
  --jeditor-pane-warmup
```

Serialize modern HTML rendering on the EDT:

```shell
java -jar target/jasper-stress-test-1.0-SNAPSHOT.jar \
  --tasks 10000 \
  --threads 40 \
  --edt-rendering
```

Serialize legacy HTML rendering on the EDT:

```shell
java -jar target/jasper-stress-test-1.0-SNAPSHOT.jar \
  --tasks 10000 \
  --threads 40 \
  --legacy-jeditor-pane-processor \
  --edt-rendering
```

## Golden reference

The checked-in reference is:

```text
src/main/resources/reference/html-print-reference.json
```

After intentionally changing the report, regenerate it safely with:

```shell
java -jar target/jasper-stress-test-1.0-SNAPSHOT.jar \
  --generate-golden src/main/resources/reference/html-print-reference.json
```

This command overwrites the specified file. It does not load or compare against
the existing golden reference. It fills once using the modern processor with
EDT delegation, captures the rendered HTML snapshots, and serializes the record
structure with Jackson.

Rebuild the executable JAR after updating a report or golden resource:

```shell
mvn clean package
```

## Results and failures

At the end of a stress run, the application prints:

- Requested runs and executor configuration
- Enabled processor, warm-up, and EDT options
- Successful runs
- Verification mismatches
- Runs that failed with an exception
- Elapsed time and throughput

For the first verification mismatch, the expected and actual snapshots are
printed as pretty-formatted JSON. For the first exception, the stack trace is
printed. The process fails after completion if any mismatch or exception was
recorded.

Mismatch frequency is timing-, JVM-, platform-, and workload-dependent. A run
with zero mismatches is evidence for that particular run, not a general
thread-safety guarantee for Swing.

## Project layout

```text
src/main/java/com/example/reports/   Stress runner, verifier, and workarounds
src/main/resources/reports/          Single JRXML report
src/main/resources/reference/        Golden rendered-HTML snapshot
```
