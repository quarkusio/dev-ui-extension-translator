# dev-ui-translator

This project provides a Quarkus-based Picocli application that externalizes user-visible text from Dev UI pages in a Quarkus extension, builds `en.js`, and generates translations for additional locales using LangChain4J with OpenAI.

## Requirements

- Java 21+
- An OpenAI API key available as `OPENAI_API_KEY` or via the `--openai-api-key` option

## Usage

Package and run the CLI:

```shell
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar --help
```

Translate a Dev UI from an extension checkout:

```shell
java -jar target/quarkus-app/quarkus-run.jar \
  --openai-api-key=$OPENAI_API_KEY \
  --languages fr,de \
  --dialects fr-FR,fr-CA,de-AT,de-CH \
  /path/to/your/extension
```

Key behaviors:

- Scans `deployment/src/main/resources/dev-ui` for `.js` pages and replaces string literals with `msg(..., { id: 'artifact-key' })` lookups.
- Adds `en.js` under `dev-ui/i18n/` (merging existing entries) and auto-creates localized files for the requested languages/dialects.
- Incorporates the runtime module description as `<artifactId>-meta-description`.
