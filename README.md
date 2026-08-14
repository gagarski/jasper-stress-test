# JasperReports HTML subreport example

This Maven project compiles a main JRXML design, resolves an HTML-markup
subreport from a JRXML location through a custom repository service, fills the
reports with one dummy record, and exports the result to PDF.

`ClasspathJrxmlRepositoryService` is configured with the classpath base folder
`reports`. During application startup, it browses that folder recursively and
compiles every JRXML design into a cached `ReportResource`. Locations exposed
by the repository are relative to the base folder.

After repository initialization, the application fetches `main_report.jrxml`
from the repository. Its subreport expression contains the hardcoded repository
location `html_subreport.jrxml`, so no report-location parameter is required.
Runtime repository lookups are cache-only; missing or invalid JRXML fails during
repository initialization rather than during report filling.

## Run

```shell
mvn clean compile exec:java
```

The generated file is:

```text
target/generated-reports/html-subreport-example.pdf
```

Pass a custom output path as the first application argument if needed:

```shell
mvn compile exec:java -Dexec.args="output/example.pdf"
```

## Stress test

`JasperStressTestApplication` defaults to 1,000,000 report fills on an executor
with twice as many threads as available processors. Every filled `JasperPrint`
is inspected and its rendered HTML styled-text structure is compared with a
hardcoded known-good reference.

```shell
mvn compile exec:java "-Dexec.mainClass=com.example.reports.JasperStressTestApplication"
```

For a shorter smoke run, pass the desired run count:

```shell
mvn compile exec:java "-Dexec.mainClass=com.example.reports.JasperStressTestApplication" "-Dexec.args=1000"
```

JasperReports 7 uses the new Jackson-based JRXML representation. In this
format, text-element properties such as `markup="html"` are attributes of
`<element kind="staticText">`, rather than a nested legacy `<textElement>` tag.
