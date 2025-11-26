package io.quarkus;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface TranslationAiService {

    @SystemMessage("""
            You translate English UI strings, used in Quarkus Dev UI pages, to {language}.
            Maintain placeholder variables like ${0} and keep punctuation intact.
            Return only the translated text.
            """)
    String translate(@V("language") String language, @UserMessage String text);
}
