/*
 * Copyright 2026 Viscosiety B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.viscosiety.classloaders;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.frankframework.configuration.IbisContext;
import org.frankframework.util.AppConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Since Frank!Framework 10.4 a configuration reload destroys the configuration's class loader and
 * builds a new one ({@code IbisContext.reload(String)}); there is no {@code reload()} on the class
 * loader any more. {@link GitClassLoader#checkout} therefore only moves the clone, and "the reload
 * sees the new ref" is observed here as: a fresh loader configured over the same clone reports that
 * ref as its {@code configuration.version}.
 */
class GitClassLoaderCheckoutTest {

    @TempDir Path tmp;
    private File work;
    private File remote;
    private GitClassLoader loader;
    private IbisContext ibisContext;

    @BeforeEach
    void setUp() throws Exception {
        // Bare "origin" with main (Configuration.xml v1) and a draft branch (v2).
        work = tmp.resolve(TempGitRepo.WORK_DIR).toFile();
        remote = tmp.resolve(TempGitRepo.REMOTE_DIR).toFile();
        ibisContext = mock(IbisContext.class);
        loader = TempGitRepo.configuredLoader(tmp, ibisContext, "demo");
    }

    @AfterEach
    void tearDown() {
        loader.destroy();
    }

    @Test
    void startsOnDefaultRef() {
        assertEquals("main", loader.currentRef());
        assertTrue(loader.isDefaultRef());
        assertSame(loader, GitClassLoader.lookup("demo"));
    }

    /** What F!F 10.4's reload does: destroy the loader and configure a new one over the clone. */
    private GitClassLoader reloadLikeFrankFramework() throws Exception {
        loader.destroy();
        GitClassLoader fresh = new GitClassLoader(getClass().getClassLoader());
        fresh.setRepoUrl(remote.toURI().toString());
        fresh.setRepoSubdir("ff-configurations/demo");
        fresh.setLocalPath(tmp.resolve(TempGitRepo.CLONE_DIR).toString());
        fresh.setConfigurationName("demo");
        fresh.afterPropertiesSet();
        loader = fresh;
        return fresh;
    }

    @Test
    void checkoutSwitchesToBranchAndTheNextLoaderLoadsIt() throws Exception {
        loader.checkout("assistant/demo/draft-abc123");

        assertEquals("assistant/demo/draft-abc123", loader.currentRef());
        assertFalse(loader.isDefaultRef());
        String content = Files.readString(tmp.resolve("clone/ff-configurations/demo/Configuration.xml"));
        assertTrue(content.contains("version=\"2\""));

        GitClassLoader fresh = reloadLikeFrankFramework();
        assertEquals("assistant/demo/draft-abc123", fresh.currentRef());
        // The draft is not mistaken for the default branch by the new loader.
        assertFalse(fresh.isDefaultRef());
        assertSame(fresh, GitClassLoader.lookup("demo"));
        assertEquals("assistant/demo/draft-abc123", AppConstants.getInstance(fresh).getProperty("configuration.version"));
    }

    @Test
    void checkoutBackToDefaultRefRestoresMain() throws Exception {
        loader.checkout("assistant/demo/draft-abc123");
        reloadLikeFrankFramework();

        loader.checkout("main");

        assertEquals("main", loader.currentRef());
        assertTrue(loader.isDefaultRef());
        String content = Files.readString(tmp.resolve("clone/ff-configurations/demo/Configuration.xml"));
        assertTrue(content.contains("version=\"1\""));
        GitClassLoader fresh = reloadLikeFrankFramework();
        assertTrue(fresh.isDefaultRef());
        assertEquals("main", AppConstants.getInstance(fresh).getProperty("configuration.version"));
    }

    @Test
    void unknownRefThrowsAndLeavesHeadUntouched() {
        assertThrows(Exception.class, () -> loader.checkout("assistant/demo/does-not-exist"));

        assertEquals("main", loader.currentRef());
    }

    @Test
    void validateRefRejectsUnsafeNames() {
        assertThrows(IllegalArgumentException.class, () -> GitClassLoader.validateRef("../etc"));
        assertThrows(IllegalArgumentException.class, () -> GitClassLoader.validateRef("a..b"));
        assertThrows(IllegalArgumentException.class, () -> GitClassLoader.validateRef("-evil"));
        assertThrows(IllegalArgumentException.class, () -> GitClassLoader.validateRef("a b"));
        assertThrows(IllegalArgumentException.class, () -> GitClassLoader.validateRef(""));
        assertThrows(IllegalArgumentException.class, () -> GitClassLoader.validateRef(null));
        // git's own check-ref-format rules: ".lock" names git's ref lockfiles, and HEAD is a
        // symbolic ref, not a branch (accepting it would make "switch to HEAD" a silent no-op).
        assertThrows(IllegalArgumentException.class, () -> GitClassLoader.validateRef("x.lock"));
        assertThrows(IllegalArgumentException.class, () -> GitClassLoader.validateRef("assistant/demo/x.lock"));
        assertThrows(IllegalArgumentException.class, () -> GitClassLoader.validateRef("HEAD"));
        assertDoesNotThrow(() -> GitClassLoader.validateRef("assistant/demo/draft-abc123"));
        assertDoesNotThrow(() -> GitClassLoader.validateRef("main"));
        // Fully-qualified forms stay valid here on purpose -- they are not traversal risks, and
        // checkout() answers them with the more useful "does not exist on the remote".
        assertDoesNotThrow(() -> GitClassLoader.validateRef("refs/heads/main"));
        assertDoesNotThrow(() -> GitClassLoader.validateRef("origin/main"));
    }

    /**
     * Only a bare branch name may reach the remote. Whether a ref is rejected by
     * {@link GitClassLoader#validateRef} (HEAD, {@code a..b}, {@code x.lock}) or by the
     * remote-branch resolution inside {@code checkout} ({@code refs/heads/main},
     * {@code origin/main} -- checkout prefixes the remote name itself, so these resolve to
     * nothing) matters to the servlet's status code, but not here: what must hold for all of them
     * is that HEAD does not move.
     */
    @Test
    void checkoutRejectsNonBranchRefs() {
        for (String ref : new String[] { "refs/heads/main", "origin/main", "HEAD", "a..b", "x.lock" }) {
            assertThrows(Exception.class, () -> loader.checkout(ref), "expected checkout to reject [" + ref + "]");
            assertEquals("main", loader.currentRef(), "checkout of [" + ref + "] moved HEAD");
        }
    }

    @Test
    void destroyRemovesRegistryEntry() {
        loader.destroy();
        assertNull(GitClassLoader.lookup("demo"));
    }

    @Test
    void checkoutSameRefAgainResetsToRemoteTip() throws Exception {
        loader.checkout("assistant/demo/draft-abc123");

        // Advance the draft branch upstream (v3) after it is already checked out locally, then
        // push that new tip to the bare "origin" the loader's clone fetches from.
        try (Git git = Git.open(work)) {
            git.checkout().setName("assistant/demo/draft-abc123").call();
            File cfg = new File(work, "ff-configurations/demo/Configuration.xml");
            Files.writeString(cfg.toPath(), "<Configuration version=\"3\"/>");
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("v3").call();
            git.checkout().setName("main").call();
            git.push().setRemote(remote.toURI().toString())
                    .setRefSpecs(new RefSpec("+assistant/demo/draft-abc123:refs/heads/assistant/demo/draft-abc123"))
                    .call();
        }

        loader.checkout("assistant/demo/draft-abc123");

        assertEquals("assistant/demo/draft-abc123", loader.currentRef());
        String content = Files.readString(tmp.resolve("clone/ff-configurations/demo/Configuration.xml"));
        assertTrue(content.contains("version=\"3\""));
    }

    /**
     * F!F's real reload ({@code IbisContext.reload(name)}) destroys this classloader instance
     * and builds a brand new one over the same on-disk clone. If a draft branch was checked out
     * at the time, that new instance's {@code configure()} must still recognise "main" as the
     * default ref -- not redefine "default" as whatever branch the clone happens to be sitting on.
     */
    @Test
    void defaultRefSurvivesClassloaderRecreation() throws Exception {
        loader.checkout("assistant/demo/draft-abc123");
        loader.destroy();

        GitClassLoader recreated = new GitClassLoader(getClass().getClassLoader());
        recreated.setRepoUrl(remote.toURI().toString());
        recreated.setRepoSubdir("ff-configurations/demo");
        recreated.setLocalPath(tmp.resolve("clone").toString());
        recreated.setConfigurationName("demo");
        recreated.afterPropertiesSet();
        loader = recreated; // let tearDown destroy this instance too

        assertEquals("assistant/demo/draft-abc123", recreated.currentRef());
        assertFalse(recreated.isDefaultRef());

        recreated.checkout("main");

        assertTrue(recreated.isDefaultRef());
    }
}
