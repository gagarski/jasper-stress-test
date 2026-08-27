# JasperReports HTML concurrency stress test

This project exercises concurrent JasperReports fills containing Swing-rendered
HTML markup. It is intended to reproduce and compare initialization and
threading behavior in JasperReports' modern `HtmlEditorKitMarkupProcessor` and
legacy `JEditorPaneHtmlMarkupProcessor` implementations. It also contains an
experimental CSS4J-backed processor for comparing a non-Swing parsing and CSS
style-resolution path, plus a Flying Saucer-backed processor that uses Jsoup to
normalize tolerant HTML fragments to XHTML before resolving CSS.

The application:

1. Compiles a single JRXML report from the classpath.
2. Submits fill operations to a fixed-size executor.
3. Uses a non-empty `JREmptyDataSource(1)` for every fill.
4. Extracts every HTML-rendered text element from the resulting `JasperPrint`.
5. Compares its text, geometry, global attributes, and style runs with a JSON
   golden reference.
6. Displays live completion progress with count, rate, elapsed time, and ETA.
7. Reports successful fills, verification mismatches, and exceptions.

The reduced report contains three non-trivial `markup="html"` text elements and
does not use subreports or a custom JasperReports repository service. Its first
element includes a fully closed definition list, intentionally exercising a
Swing HTML DTD path that the representative editor-kit warm-up does not touch.

## Requirements

- Java 17 or newer
- Maven 3.6.3 or newer

CSS4J 6.2 is not published to Maven Central. The POM therefore declares the
project's release repository at `https://css4j.github.io/maven/`. The
Validator.nu HTML parser dependency is available from Maven Central.

Flying Saucer 9.13.3 and Jsoup 1.23.1 are available from Maven Central. Flying
Saucer 9.13.x is the current release line compatible with this project's Java
17 baseline; Flying Saucer 10 requires Java 21.

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
| `--css4j-html-processor` | `false` | Selects the experimental CSS4J/Validator.nu processor. It cannot be combined with either of the other processor-selection switches. |
| `--flying-saucer-html-processor` | `false` | Selects the experimental Flying Saucer/Jsoup processor. It cannot be combined with either of the other processor-selection switches. |
| `--edt-rendering` | `false` | Delegates every HTML conversion to the Swing EDT. It can be combined with any selected HTML processor. |
| `--generate-golden FILE` | unset | Leaves stress-test mode, fills once using the modern processor on the EDT, and writes pretty-printed golden JSON to `FILE`. It cannot be combined with any processor-selection switch. |
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

Run the CSS4J proof of concept concurrently:

```shell
java -jar target/jasper-stress-test-1.0-SNAPSHOT.jar \
  --tasks 10000 \
  --threads 40 \
  --css4j-html-processor
```

The CSS4J mode can also be combined with `--edt-rendering`. This is useful as a
control configuration even though CSS4J itself does not depend on Swing.

The processor deliberately retains JasperReports' recursive conversion and
list/flow state model. Validator.nu supplies the tolerant HTML DOM and CSS4J
supplies computed styles; the POC does not attempt to add HTML features beyond
the attributes Jasper's standard converter maps to styled text.

The report uses valid legacy font sizes `6` and `2`. The CSS4J adapter maps the
seven-step `<font size>` scale to Jasper/Swing's point-size table and customizes
CSS4J's user-agent rules so `sub` and `sup` retain their inherited size while
still producing superscript/subscript attributes. These compatibility rules let
the CSS4J processor validate against the same Swing-generated golden reference.
An explicitly supplied inline CSS `font-size` continues through CSS4J normally.

Run the Flying Saucer proof of concept concurrently:

```shell
java -jar target/jasper-stress-test-1.0-SNAPSHOT.jar \
  --tasks 10000 \
  --threads 40 \
  --flying-saucer-html-processor
```

The Flying Saucer processor follows the same boundary as the CSS4J processor:
Jasper's recursive traversal, flow/list state, hyperlink creation, and styled
text emission are retained. Jsoup first turns ordinary HTML fragments into
well-formed XHTML; Flying Saucer then parses that XHTML and supplies its CSS 2.1
cascade and calculated styles.

Compatibility handling is deliberately narrow. The adapter maps the absolute
legacy `<font size="1">` through `<font size="7">` scale to the same point sizes
used by Jasper's Swing processor, translates `<font color>` and `<font face>`
hints into CSS, and makes `sub` and `sup` inherit font size while preserving
their vertical-align semantics. It also accounts for Flying Saucer's internal
list representation of multi-value `text-decoration`. These rules allow the
Flying Saucer processor to validate against the existing Swing-generated golden
reference without expanding the HTML feature set.

Flying Saucer mode is also combinable with `--edt-rendering` as a control. The
processor creates a fresh Flying Saucer context for each conversion; EDT mode
therefore measures serialization overhead rather than being required by the
adapter's design.

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

While the run is active, a terminal progress bar tracks completed fills. It is
advanced by the executor coordinator as futures complete, so it measures
finished work rather than merely submitted work.

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
