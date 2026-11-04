package com.viscosiety.viscorunner;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The three published modules and the CI settings point at this project's GitLab Maven registry. */
class PublishWiringTest {

    private static final Path REPO = Path.of("..");
    private static final String REGISTRY_URL = "${env.CI_API_V4_URL}/projects/${env.CI_PROJECT_ID}/packages/maven";

    @Test
    void thePublishedModulesDeployToTheProjectRegistry() throws IOException {
        for (String pom : List.of("viscolink/pom.xml", "packs/health/pom.xml", "viscostore/pom.xml")) {
            String text = Files.readString(REPO.resolve(pom));
            assertTrue(text.contains("<distributionManagement>"), pom + " has distributionManagement");
            assertTrue(text.contains("<id>gitlab-maven</id>"), pom + " names the gitlab-maven server");
            assertTrue(text.contains("<url>" + REGISTRY_URL + "</url>"), pom + " deploys to the project's Maven registry");
        }
    }

    @Test
    void theModulesThatAreNotComponentsDoNotDeploy() throws IOException {
        for (String pom : List.of("viscorunner/pom.xml", "packs/health/util/hl7util/pom.xml", "pom.xml")) {
            String text = Files.readString(REPO.resolve(pom));
            assertTrue(!text.contains("<distributionManagement>"), pom + " is not published");
        }
    }

    @Test
    void ciSettingsAuthenticateTheRegistryWithTheJobToken() throws IOException {
        String settings = Files.readString(REPO.resolve(".mvn/settings.xml"));
        assertTrue(settings.contains("<id>gitlab-maven</id>"), "server gitlab-maven");
        assertTrue(settings.contains("<name>Job-Token</name>"), "Job-Token header");
        assertTrue(settings.contains("<value>${env.CI_JOB_TOKEN}</value>"), "from CI_JOB_TOKEN");
    }

    @Test
    void thePublishScriptsExistAndAreExecutable() {
        for (String script : List.of("scripts/ci/stage-publish.sh", "scripts/ci/publish.sh")) {
            assertTrue(Files.isExecutable(REPO.resolve(script)), script + " is executable");
        }
    }
}
