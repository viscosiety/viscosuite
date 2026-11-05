package com.viscosiety.viscorunner;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** The ROOT landing page shows a card per component it can see in components.json. */
class LandingPageTest {

    private static final Path PAGE = Path.of("src/webapp/ROOT/index.html");

    @Test
    void theConsoleCardExistsHiddenAndIsShownForTheConsoleComponent() throws Exception {
        String html = Files.readString(PAGE);
        assertTrue(html.contains("id=\"card-console\""), "a card for the ViscoForge console");
        assertTrue(html.contains("href=\"/console/ui/\""), "it opens the console UI");
        assertTrue(html.matches("(?s).*<a class=\"card\" id=\"card-console\" href=\"/console/ui/\" hidden>.*"), "hidden until components.json names it");
        assertTrue(html.contains("comp.id === 'console'"), "components.json's console entry unhides it");
    }
}
