package org.codelibs.fess.suggest.settings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.codelibs.core.lang.StringUtil;
import org.codelibs.fess.suggest.analysis.SuggestAnalyzer;
import org.codelibs.fess.suggest.exception.SuggestSettingsException;
import org.elasticsearch.action.admin.indices.analyze.AnalyzeResponse;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse;
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;

public class AnalyzerSettings {
    public static final String readingAnalyzerName = "reading_analyzer";
    public static final String readingTermAnalyzerName = "reading_term_analyzer";
    public static final String normalizeAnalyzerName = "normalize_analyzer";
    public static final String contentsAnalyzerName = "contents_analyzer";
    public static final String contentsReadingAnalyzerName = "contents_reading_analyzer";

    protected final Client client;
    protected final String analyzerSettingsIndexName;
    private SuggestSettings settings;

    public static final String[] SUPPORTED_LANGUAGES = new String[] { "ar", "bg", "bn", "ca", "cs", "da", "de", "el", "en", "es", "et",
            "fa", "fi", "fr", "gu", "he", "hi", "hr", "hu", "id", "it", "ja", "ko", "lt", "lv", "mk", "ml", "nl", "no", "pa", "pl", "pt",
            "ro", "ru", "si", "sq", "sv", "ta", "te", "th", "tl", "tr", "uk", "ur", "vi", "zh-cn", "zh-tw" };

    public AnalyzerSettings(final Client client, SuggestSettings settings, final String settingsIndexName) {
        this.client = client;
        this.settings = settings;
        analyzerSettingsIndexName = createAnalyzerSettingsIndexName(settingsIndexName);
    }

    public void init() {
        try {
            final IndicesExistsResponse response =
                    client.admin().indices().prepareExists(analyzerSettingsIndexName).execute().actionGet(settings.getIndicesTimeout());
            if (!response.isExists()) {
                createAnalyzerSettings(loadIndexSettings(), loadIndexMapping());
            }
        } catch (final IOException e) {
            throw new SuggestSettingsException("Failed to create mappings.");
        }
    }

    public String getAnalyzerSettingsIndexName() {
        return analyzerSettingsIndexName;
    }

    public String getReadingAnalyzerName(final String lang) {
        return isSupportedLanguage(lang) ? readingAnalyzerName + '_' + lang : readingAnalyzerName;
    }

    public String getReadingTermAnalyzerName(final String lang) {
        return isSupportedLanguage(lang) ? readingTermAnalyzerName + '_' + lang : readingTermAnalyzerName;
    }

    public String getNormalizeAnalyzerName(final String lang) {
        return isSupportedLanguage(lang) ? normalizeAnalyzerName + '_' + lang : normalizeAnalyzerName;
    }

    public String getContentsAnalyzerName(final String lang) {
        return isSupportedLanguage(lang) ? contentsAnalyzerName + '_' + lang : contentsAnalyzerName;
    }

    public String getContentsReadingAnalyzerName(final String lang) {
        return isSupportedLanguage(lang) ? contentsReadingAnalyzerName + '_' + lang : contentsReadingAnalyzerName;
    }

    public void updateAnalyzer(final Map<String, Object> settings) {
        client.admin().indices().prepareCreate(analyzerSettingsIndexName).setSettings(settings).execute()
                .actionGet(this.settings.getIndicesTimeout());
    }

    protected void deleteAnalyzerSettings() {
        client.admin().indices().prepareDelete(analyzerSettingsIndexName).execute().actionGet(settings.getIndicesTimeout());
    }

    protected void createAnalyzerSettings(final String settings, final String mappings) {
        client.admin().indices().prepareCreate(analyzerSettingsIndexName).setSettings(settings, XContentType.JSON)
            .addMapping("_doc", mappings, XContentType.JSON).execute()
                .actionGet(this.settings.getIndicesTimeout());
    }

    protected void createAnalyzerSettings(final Map<String, Object> settings, final Map<String, Object> mappings) {
        client.admin().indices().prepareCreate(analyzerSettingsIndexName).setSettings(settings).execute()
                .actionGet(this.settings.getIndicesTimeout());
    }

    protected String createAnalyzerSettingsIndexName(final String settingsIndexName) {
        return settingsIndexName + "_analyzer";
    }

    protected String loadIndexSettings() throws IOException {
        final String dictionaryPath = System.getProperty("fess.dictionary.path", StringUtil.EMPTY);
        final StringBuilder sb = new StringBuilder();
        try (BufferedReader br =
                new BufferedReader(new InputStreamReader(this.getClass().getClassLoader()
                        .getResourceAsStream("suggest_indices/suggest_analyzer.json")));) {

            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString().replaceAll(Pattern.quote("${fess.dictionary.path}"), dictionaryPath);
    }

    protected String loadIndexMapping() throws IOException {
        final StringBuilder sb = new StringBuilder();
        try (BufferedReader br =
                 new BufferedReader(new InputStreamReader(this.getClass().getClassLoader()
                     .getResourceAsStream("suggest_indices/analyzer/mapping-default.json")));) {

            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    public class DefaultContentsAnalyzer implements SuggestAnalyzer {
        @Override
        public List<AnalyzeResponse.AnalyzeToken> analyze(final String text, final String lang) {
            final AnalyzeResponse analyzeResponse =
                    client.admin().indices().prepareAnalyze(analyzerSettingsIndexName, text).setAnalyzer(getContentsAnalyzerName(lang))
                            .execute().actionGet(settings.getIndicesTimeout());
            return analyzeResponse.getTokens();
        }

        @Override
        public List<AnalyzeResponse.AnalyzeToken> analyzeAndReading(final String text, final String lang) {
            try {
                final AnalyzeResponse analyzeResponse =
                        client.admin().indices().prepareAnalyze(analyzerSettingsIndexName, text)
                                .setAnalyzer(getContentsReadingAnalyzerName(lang)).execute().actionGet(settings.getIndicesTimeout());
                return analyzeResponse.getTokens();
            } catch (final IllegalArgumentException e) {
                return analyze(text, lang);
            }
        }
    }

    public static boolean isSupportedLanguage(final String lang) {
        return (StringUtil.isNotBlank(lang) && Stream.of(SUPPORTED_LANGUAGES).anyMatch(lang::equals));
    }

    public Set<String> checkAnalyzer() {
        final String text = "text";
        final Set<String> undefinedAnalyzerSet = new HashSet<>();
        for (final String lang : SUPPORTED_LANGUAGES) {
            final String readingAnalyzer = getReadingAnalyzerName(lang);
            try {
                client.admin().indices().prepareAnalyze(analyzerSettingsIndexName, text).setAnalyzer(readingAnalyzer).execute()
                        .actionGet(settings.getIndicesTimeout());
            } catch (final IllegalArgumentException e) {
                undefinedAnalyzerSet.add(readingAnalyzer);
            }

            final String readingTermAnalyzer = getReadingTermAnalyzerName(lang);
            try {
                client.admin().indices().prepareAnalyze(analyzerSettingsIndexName, text).setAnalyzer(readingTermAnalyzer).execute()
                        .actionGet(settings.getIndicesTimeout());
            } catch (final IllegalArgumentException e) {
                undefinedAnalyzerSet.add(readingTermAnalyzer);
            }

            final String normalizeAnalyzer = getNormalizeAnalyzerName(lang);
            try {
                client.admin().indices().prepareAnalyze(analyzerSettingsIndexName, text).setAnalyzer(normalizeAnalyzer).execute()
                        .actionGet(settings.getIndicesTimeout());
            } catch (final IllegalArgumentException e) {
                undefinedAnalyzerSet.add(normalizeAnalyzer);
            }

            final String contentsAnalyzer = getContentsAnalyzerName(lang);
            try {
                client.admin().indices().prepareAnalyze(analyzerSettingsIndexName, text).setAnalyzer(contentsAnalyzer).execute()
                        .actionGet(settings.getIndicesTimeout());
            } catch (final IllegalArgumentException e) {
                undefinedAnalyzerSet.add(contentsAnalyzer);
            }

            final String contentsReadingAnalyzer = getContentsReadingAnalyzerName(lang);
            try {
                client.admin().indices().prepareAnalyze(analyzerSettingsIndexName, text).setAnalyzer(contentsReadingAnalyzer).execute()
                        .actionGet(settings.getIndicesTimeout());
            } catch (final IllegalArgumentException e) {
                undefinedAnalyzerSet.add(contentsReadingAnalyzer);
            }
        }

        return undefinedAnalyzerSet;
    }

    protected Set<String> getAnalyzerNames() {
        final GetSettingsResponse response = client.admin().indices().prepareGetSettings().setIndices(analyzerSettingsIndexName).execute().actionGet();
        final Settings settings = response.getIndexToSettings().get(analyzerSettingsIndexName);
        final Settings analysisSettings = settings.getAsGroups().get("analysis");
        final Settings analyzerSettings = analysisSettings.getAsGroups().get("analyzer");
        return analyzerSettings.keySet();
    }
}
