package io.quarkus;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import javax.xml.parsers.DocumentBuilderFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@TopCommand
@Command(name = "dev-ui-translate", mixinStandardHelpOptions = true,
        description = "Externalize Dev UI text and create translations using LangChain4J")
@Singleton
public class DevUITranslatorCommand implements Runnable {

    private static final Pattern STRING_LITERAL = Pattern.compile("(['\"])((?:\\\\.|(?!\\1).)*?)\\1");
    private static final Pattern TEMPLATE_LITERAL = Pattern.compile("`((?:\\`|\\\\|[^`])*)`");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]*)\\}");
    private static final Pattern CONSTRUCTOR_PATTERN = Pattern.compile("constructor\\s*\\([^)]*\\)\\s*\\{");
    private static final Pattern EXISTING_MSG_PATTERN = Pattern.compile("msg\\s*\\(\\s*['\"]");

    private static final Map<String, List<String>> DEFAULT_DIALECTS = Map.of(
            "fr", List.of("fr-FR", "fr-CA"),
            "de", List.of("de-AT", "de-CH"));

    @Parameters(paramLabel = "<extension-root>", description = "Path to the Quarkus extension root", arity = "0..1")
    Path extensionRoot;

    @Option(names = {"-l", "--languages"}, split = ",", description = "Comma separated base languages to generate")
    List<String> languages;

    @Option(names = {"-d", "--dialects"}, split = ",",
            description = "Optional comma separated dialects (e.g. fr-CA,de-AT). Defaults are added for known languages")
    List<String> dialectOverrides;

    @Option(names = "--openai-api-key", description = "OpenAI API key (or set OPENAI_API_KEY)", defaultValue = "${OPENAI_API_KEY:}")
    String openAiApiKey;

    @Inject
    TranslationAiService translationAiService;

    @Override
    public void run() {
        ensureUserInputs();
        configureOpenAi();
        Path devUiRoot = extensionRoot.resolve("deployment/src/main/resources/dev-ui");
        if (!Files.isDirectory(devUiRoot)) {
            System.err.printf("No Dev UI resources found at %s%n", devUiRoot);
            return;
        }
        String artifactId = readRuntimeArtifactId(extensionRoot)
                .orElseGet(() -> {
                    System.err.println("Could not determine runtime artifactId, using generic key prefix");
                    return "extension";
                });

        Map<Path, Map<String, TranslationEntry>> perFileTranslations = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(devUiRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".js"))
                    .filter(path -> !path.toString().contains("/i18n/"))
                    .forEach(path -> perFileTranslations.put(path, processFile(path, artifactId)));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan Dev UI directory", e);
        }

        Map<String, TranslationEntry> englishEntries = mergeTranslations(perFileTranslations.values());
        mergeMetadataDescription(englishEntries, extensionRoot, artifactId);

        Path i18nFolder = devUiRoot.resolve("i18n");
        try {
            Files.createDirectories(i18nFolder);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create i18n folder", e);
        }

        Map<String, TranslationEntry> existingEnglish = readTranslations(i18nFolder.resolve("en.js"));
        englishEntries.putAll(existingEnglish);
        writeTranslationFile(i18nFolder, englishEntries, "en");

        Map<String, List<String>> dialectsByLanguage = resolveDialects();
        languages.stream()
                .map(String::trim)
                .filter(lang -> !lang.isBlank())
                .forEach(language -> translateLanguage(i18nFolder, englishEntries, language, dialectsByLanguage.getOrDefault(language, List.of())));

        System.out.println("Translation complete at " + LocalDateTime.now());
    }

    private void ensureUserInputs() {
        if (extensionRoot == null) {
            extensionRoot = promptForPath("Enter path to the Quarkus extension root: ");
        }
        if (languages == null || languages.isEmpty()) {
            languages = promptForList("Enter comma separated base languages (e.g. fr,de) [default: fr,de]: ");
            if (languages.isEmpty()) {
                languages = new ArrayList<>(List.of("fr", "de"));
            }
        }
        if (dialectOverrides == null) {
            dialectOverrides = promptForList("Enter optional comma separated dialects (press Enter for defaults): ");
        }
    }

    private void configureOpenAi() {
        if (openAiApiKey != null && !openAiApiKey.isBlank()) {
            System.setProperty("quarkus.langchain4j.openai.chat-model.api-key", openAiApiKey);
        }
        if (System.getProperty("quarkus.langchain4j.openai.chat-model.api-key") == null
                && System.getenv("OPENAI_API_KEY") != null) {
            System.setProperty("quarkus.langchain4j.openai.chat-model.api-key", System.getenv("OPENAI_API_KEY"));
        }
        if (System.getProperty("quarkus.langchain4j.openai.chat-model.api-key") == null) {
            System.err.println("OpenAI API key is not configured. Set --openai-api-key or OPENAI_API_KEY.");
        }
    }

    private Map<String, List<String>> resolveDialects() {
        if (dialectOverrides != null && !dialectOverrides.isEmpty()) {
            return dialectOverrides.stream()
                    .map(String::trim)
                    .filter(s -> s.contains("-"))
                    .collect(groupingBy(code -> code.substring(0, code.indexOf('-'))));
        }
        Map<String, List<String>> dialects = new LinkedHashMap<>();
        languages.forEach(lang -> dialects.put(lang, DEFAULT_DIALECTS.getOrDefault(lang, List.of())));
        return dialects;
    }

    private List<String> promptForList(String prompt) {
        String input = promptForInput(prompt);
        if (input == null || input.isBlank()) {
            return new ArrayList<>();
        }
        List<String> values = new ArrayList<>();
        for (String token : input.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isBlank()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private Path promptForPath(String prompt) {
        String input = promptForInput(prompt);
        if (input == null || input.isBlank()) {
            throw new IllegalStateException("A path to the Quarkus extension root is required.");
        }
        return Path.of(input.trim());
    }

    private String promptForInput(String prompt) {
        Console console = System.console();
        if (console != null) {
            return console.readLine(prompt);
        }
        System.out.print(prompt);
        System.out.flush();
        try {
            return new BufferedReader(new InputStreamReader(System.in)).readLine();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read input", e);
        }
    }

    private Map<String, TranslationEntry> processFile(Path path, String artifactId) {
        try {
            String content = Files.readString(path);
            Set<String> usedKeys = new LinkedHashSet<>();
            Map<String, String> userStrings = extractUserStrings(content, artifactId, usedKeys);
            List<TemplateLocalization> templates = extractTemplateStrings(content, artifactId, usedKeys);
            String updated = applyLocalization(content, userStrings, templates);
            if (!updated.equals(content)) {
                Files.writeString(path, updated);
                System.out.printf("Updated %s with localization hooks%n", path.getFileName());
            }
            Map<String, TranslationEntry> translations = userStrings.entrySet().stream()
                    .collect(toMap(Map.Entry::getValue, entry -> new TranslationEntry(entry.getKey(), false), (a, b) -> a, LinkedHashMap::new));
            templates.forEach(template -> translations.put(template.key(), new TranslationEntry(template.numberedTemplate(), true)));
            return translations;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to process " + path, e);
        }
    }

    private Map<String, String> extractUserStrings(String content, String artifactId, Set<String> usedKeys) {
        Matcher matcher = STRING_LITERAL.matcher(content);
        Map<String, String> entries = new LinkedHashMap<>();
        while (matcher.find()) {
            String literal = matcher.group(2);
            if (!isUserVisibleCandidate(literal, matcher.start(), content)) {
                continue;
            }
            String key = buildKey(artifactId, literal, usedKeys);
            entries.putIfAbsent(literal, key);
        }
        return entries;
    }

    private List<TemplateLocalization> extractTemplateStrings(String content, String artifactId, Set<String> usedKeys) {
        List<TemplateLocalization> templates = new ArrayList<>();
        Matcher matcher = TEMPLATE_LITERAL.matcher(content);
        while (matcher.find()) {
            String templateBody = matcher.group(1);
            if (!templateBody.contains("${")) {
                continue;
            }
            String candidate = stripPlaceholders(templateBody);
            if (!isUserVisibleCandidate(candidate, matcher.start(), content)) {
                continue;
            }
            String numbered = normalizeTemplatePlaceholders(templateBody);
            String codeTemplate = buildTemplatePlaceholders(templateBody);
            String key = buildKey(artifactId, candidate, usedKeys);
            String indent = determineIndent(content, matcher.start());
            templates.add(new TemplateLocalization("`" + templateBody + "`", numbered, codeTemplate, key, indent));
        }
        return templates;
    }

    private boolean isUserVisibleCandidate(String literal, int startIndex, String content) {
        String trimmed = literal.trim();
        if (trimmed.length() < 3 || trimmed.length() > 120) {
            return false;
        }
        if (!trimmed.chars().anyMatch(Character::isLetter)) {
            return false;
        }
        if (!trimmed.contains(" ") && trimmed.matches("[A-Za-z0-9._/@:-]+")) {
            return false;
        }
        if (trimmed.startsWith("http") || trimmed.startsWith("@")) {
            return false;
        }
        if (isImportLiteral(startIndex, content)) {
            return false;
        }
        return !EXISTING_MSG_PATTERN.matcher(content.substring(Math.max(0, startIndex - 8), Math.min(content.length(), startIndex + trimmed.length() + 8))).find();
    }

    private boolean isImportLiteral(int startIndex, String content) {
        int lineStart = content.lastIndexOf('\n', startIndex);
        int lineEnd = content.indexOf('\n', startIndex);
        if (lineStart < 0) {
            lineStart = 0;
        }
        if (lineEnd < 0) {
            lineEnd = content.length();
        }
        String line = content.substring(lineStart, lineEnd);
        return line.stripLeading().startsWith("import ");
    }

    private String buildKey(String artifactId, String literal, Set<String> usedKeys) {
        String sanitized = literal.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        sanitized = sanitized.replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (sanitized.isBlank()) {
            sanitized = "text";
        }
        String candidate = artifactId + "-" + sanitized;
        int counter = 1;
        while (usedKeys.contains(candidate)) {
            candidate = artifactId + "-" + sanitized + "_" + counter++;
        }
        usedKeys.add(candidate);
        return candidate;
    }

    private String normalizeTemplatePlaceholders(String template) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder builder = new StringBuilder();
        int last = 0;
        int index = 0;
        while (matcher.find()) {
            builder.append(template, last, matcher.start());
            builder.append("${").append(index++).append("}");
            last = matcher.end();
        }
        builder.append(template.substring(last));
        return builder.toString();
    }

    private String buildTemplatePlaceholders(String template) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder builder = new StringBuilder();
        int last = 0;
        int index = 0;
        while (matcher.find()) {
            builder.append(template, last, matcher.start());
            builder.append("${placeholder").append(index++).append("}");
            last = matcher.end();
        }
        builder.append(template.substring(last));
        return builder.toString();
    }

    private String stripPlaceholders(String template) {
        return PLACEHOLDER_PATTERN.matcher(template).replaceAll(" ").trim();
    }

    private String determineIndent(String content, int startIndex) {
        int lineStart = content.lastIndexOf('\n', startIndex);
        int cursor = lineStart + 1;
        StringBuilder indent = new StringBuilder();
        while (cursor < startIndex && Character.isWhitespace(content.charAt(cursor))) {
            indent.append(content.charAt(cursor));
            cursor++;
        }
        return indent.toString();
    }

    private String applyLocalization(String content, Map<String, String> replacements, List<TemplateLocalization> templates) {
        String updated = content;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            String literal = entry.getKey();
            String key = entry.getValue();
            Pattern literalPattern = Pattern.compile("(['\"])" + Pattern.quote(literal) + "\\1");
            Matcher matcher = literalPattern.matcher(updated);
            if (matcher.find()) {
                String quote = matcher.group(1);
                String replacement = "msg(" + quote + literal + quote + ", { id: '" + key + "' })";
                updated = matcher.replaceFirst(Matcher.quoteReplacement(replacement));
            }
        }

        boolean usesTemplates = !templates.isEmpty();
        for (TemplateLocalization template : templates) {
            Pattern literalPattern = Pattern.compile(Pattern.quote(template.literal()));
            Matcher matcher = literalPattern.matcher(updated);
            if (matcher.find()) {
                usesTemplates = true;
                String replacement = wrapTemplateWithMsg(template);
                updated = matcher.replaceFirst(Matcher.quoteReplacement(replacement));
            }
        }

        updated = ensureLocalizationImport(updated, usesTemplates);
        updated = ensureLocaleUpdates(updated);
        return updated;
    }

    private String ensureLocalizationImport(String content, boolean needsTemplateSupport) {
        List<String> lines = new ArrayList<>(content.lines().toList());
        String importLine = needsTemplateSupport
                ? "import { msg, str, updateWhenLocaleChanges } from 'localization';"
                : "import { msg, updateWhenLocaleChanges } from 'localization';";
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("from 'localization'") || lines.get(i).contains("from \"localization\"")) {
                if (!lines.get(i).equals(importLine)) {
                    lines.set(i, importLine);
                }
                return String.join("\n", lines);
            }
        }
        int insertPos = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("import ")) {
                insertPos = i + 1;
            }
        }
        lines.add(insertPos, importLine);
        return String.join("\n", lines);
    }

    private String ensureLocaleUpdates(String content) {
        if (content.contains("updateWhenLocaleChanges(this)")) {
            return content;
        }
        Matcher constructorMatcher = CONSTRUCTOR_PATTERN.matcher(content);
        if (constructorMatcher.find()) {
            int insertPos = constructorMatcher.end();
            return content.substring(0, insertPos) + "\n        updateWhenLocaleChanges(this);" + content.substring(insertPos);
        }
        return content;
    }

    private String wrapTemplateWithMsg(TemplateLocalization template) {
        String indent = template.indent();
        StringBuilder builder = new StringBuilder();
        builder.append(indent).append("(() => {\n");
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template.literal());
        int index = 0;
        while (matcher.find()) {
            builder.append(indent)
                    .append("    const placeholder")
                    .append(index)
                    .append(" = ")
                    .append(matcher.group(1).trim())
                    .append(";\n");
            index++;
        }
        builder.append(indent)
                .append("    return msg(str`")
                .append(escapeBackticks(template.codeTemplate()))
                .append("`, { id: '")
                .append(template.key())
                .append("' });\n")
                .append(indent)
                .append("})()");
        return builder.toString();
    }

    private Map<String, TranslationEntry> mergeTranslations(Iterable<Map<String, TranslationEntry>> translations) {
        Map<String, TranslationEntry> merged = new LinkedHashMap<>();
        translations.forEach(map -> merged.putAll(map));
        return merged;
    }

    private Optional<String> readRuntimeArtifactId(Path extensionRoot) {
        Path runtimePom = extensionRoot.resolve("runtime/pom.xml");
        if (!Files.exists(runtimePom)) {
            return Optional.empty();
        }
        try {
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(runtimePom.toFile());
            NodeList artifactNodes = document.getElementsByTagName("artifactId");
            if (artifactNodes.getLength() > 0) {
                return Optional.ofNullable(artifactNodes.item(0).getTextContent()).map(String::trim);
            }
        } catch (Exception e) {
            System.err.printf("Failed to read runtime artifactId: %s%n", e.getMessage());
        }
        return Optional.empty();
    }

    private Map<String, TranslationEntry> readTranslations(Path path) {
        if (!Files.exists(path)) {
            return new LinkedHashMap<>();
        }
        Map<String, TranslationEntry> translations = new LinkedHashMap<>();
        Pattern entryPattern = Pattern.compile("['\"]([^'\"]+)['\"]\\s*:\\s*['\"]([^'\"]*)['\"]");
        Pattern strPattern = Pattern.compile("['\"]([^'\"]+)['\"]\\s*:\\s*str`([^`]*)`");
        try {
            String content = Files.readString(path);
            Matcher matcher = entryPattern.matcher(content);
            while (matcher.find()) {
                translations.put(matcher.group(1), new TranslationEntry(matcher.group(2), false));
            }
            Matcher strMatcher = strPattern.matcher(content);
            while (strMatcher.find()) {
                translations.put(strMatcher.group(1), new TranslationEntry(normalizeTemplatePlaceholders(strMatcher.group(2)), true));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read translations", e);
        }
        return translations;
    }

    private void mergeMetadataDescription(Map<String, TranslationEntry> translations, Path extensionRoot, String artifactId) {
        Path runtimePom = extensionRoot.resolve("runtime/pom.xml");
        if (!Files.exists(runtimePom)) {
            return;
        }
        try {
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(runtimePom.toFile());
            NodeList descriptionNodes = document.getElementsByTagName("description");
            if (descriptionNodes.getLength() > 0) {
                String description = descriptionNodes.item(0).getTextContent();
                if (description != null && !description.isBlank()) {
                    translations.putIfAbsent(artifactId + "-meta-description", new TranslationEntry(description.trim(), false));
                }
            }
        } catch (Exception e) {
            System.err.printf("Failed to read runtime description: %s%n", e.getMessage());
        }
    }

    private void translateLanguage(Path i18nFolder, Map<String, TranslationEntry> baseEntries, String languageCode, List<String> dialects) {
        Map<String, TranslationEntry> languageTranslations = translateEntries(baseEntries, languageLabel(languageCode));
        writeTranslationFile(i18nFolder, languageTranslations, languageCode);
        for (String dialect : dialects) {
            String dialectLabel = languageLabel(dialect);
            Map<String, TranslationEntry> dialectTranslations = translateEntries(baseEntries, dialectLabel);
            Map<String, TranslationEntry> diff = diff(languageTranslations, dialectTranslations);
            writeTranslationFile(i18nFolder, diff, dialect);
        }
    }

    private String languageLabel(String code) {
        Locale locale = Locale.forLanguageTag(code);
        String label = locale.getDisplayName(Locale.ENGLISH);
        if (label == null || label.isBlank()) {
            return code;
        }
        return label;
    }

    private Map<String, TranslationEntry> translateEntries(Map<String, TranslationEntry> source, String languageLabel) {
        Map<String, TranslationEntry> target = new LinkedHashMap<>();
        source.forEach((key, entry) -> target.put(key, new TranslationEntry(translate(entry.value(), languageLabel), entry.template())));
        return target;
    }

    private String translate(String value, String languageLabel) {
        ManagedContext requestContext = Arc.container().requestContext();
        boolean activatedHere = false;
        if (!requestContext.isActive()) {
            requestContext.activate();
            activatedHere = true;
        }
        try {
            return translationAiService.translate(languageLabel, value);
        } catch (Exception e) {
            System.err.printf("Failed to translate '%s' to %s: %s%n", value, languageLabel, e.getMessage());
            return "<translation error>";
        } finally {
            if (activatedHere) {
                requestContext.terminate();
            }
        }
    }

    private Map<String, TranslationEntry> diff(Map<String, TranslationEntry> base, Map<String, TranslationEntry> variant) {
        Map<String, TranslationEntry> diff = new LinkedHashMap<>();
        variant.forEach((key, value) -> {
            TranslationEntry baseValue = base.get(key);
            if (baseValue == null || !baseValue.equals(value)) {
                diff.put(key, value);
            }
        });
        return diff;
    }

    private void writeTranslationFile(Path i18nFolder, Map<String, TranslationEntry> entries, String stem) {
        if (entries.isEmpty()) {
            return;
        }
        Path target = i18nFolder.resolve(stem + ".js");
        TreeMap<String, TranslationEntry> sorted = new TreeMap<>(entries);
        boolean needsStr = sorted.values().stream().anyMatch(TranslationEntry::template);
        StringBuilder builder = new StringBuilder();
        if (needsStr) {
            builder.append("import { str } from '@lit/localize';\n\n");
        }
        builder.append("export const templates = {\n");
        sorted.forEach((key, entry) -> {
            builder.append("    '").append(key).append("': ");
            if (entry.template()) {
                builder.append("str`" + escapeBackticks(entry.value()) + "`");
            } else {
                builder.append("'").append(escapeSingleQuotes(entry.value())).append("'");
            }
            builder.append(",\n");
        });
        builder.append("};\n");
        try {
            Files.writeString(target, builder.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.printf("Written %s%n", target.getFileName());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + target, e);
        }
    }

    private String escapeSingleQuotes(String value) {
        return value.replace("'", "\\'");
    }

    private String escapeBackticks(String value) {
        return value.replace("`", "\\`");
    }

    private record TemplateLocalization(String literal, String numberedTemplate, String codeTemplate, String key, String indent) {
    }

    private record TranslationEntry(String value, boolean template) {
    }
}
