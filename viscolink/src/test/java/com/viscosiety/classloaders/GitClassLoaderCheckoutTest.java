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
 * {@code AbstractClassLoader.reload()} (the method {@link GitClassLoader#checkout} calls via
 * {@code super.reload()}) does not call back into {@code IbisContext}; it only evicts this
 * classloader's cached {@link AppConstants} instance
 * ({@code AppConstants.removeInstance(this)}). {@code IbisContext.reload(String)} is the other
 * direction of that relationship -- something external calls to *drive* a classloader reload, not
 * something a classloader reload triggers. So "reload happened" is observed here as: the next
 * {@code AppConstants.getInstance(loader)} call returns a fresh instance rather than the one
 * cached before the reload.
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

    @Test
    void checkoutSwitchesToBranchAndReloadsUnconditionally() throws Exception {
        AppConstants before = AppConstants.getInstance(loader);

        loader.checkout("assistant/demo/draft-abc123");

        assertEquals("assistant/demo/draft-abc123", loader.currentRef());
        assertFalse(loader.isDefaultRef());
        String content = Files.readString(tmp.resolve("clone/ff-configurations/demo/Configuration.xml"));
        assertTrue(content.contains("version=\"2\""));
        // super.reload() evicted the cached AppConstants instance -- the next lookup is a new one,
        // and configuration.version must be set on THAT fresh instance (checkout sets it after
        // super.reload(), not before -- setting it before would just be evicted along with it).
        AppConstants after = AppConstants.getInstance(loader);
        assertNotSame(before, after);
        assertEquals("assistant/demo/draft-abc123", after.getProperty("configuration.version"));
    }

    @Test
    void checkoutBackToDefaultRefRestoresMain() throws Exception {
        loader.checkout("assistant/demo/draft-abc123");
        AppConstants beforeSecondCheckout = AppConstants.getInstance(loader);

        loader.checkout("main");

        assertEquals("main", loader.currentRef());
        assertTrue(loader.isDefaultRef());
        String content = Files.readString(tmp.resolve("clone/ff-configurations/demo/Configuration.xml"));
        assertTrue(content.contains("version=\"1\""));
        AppConstants afterSecondCheckout = AppConstants.getInstance(loader);
        assertNotSame(beforeSecondCheckout, afterSecondCheckout);
        assertEquals("main", afterSecondCheckout.getProperty("configuration.version"));
    }

    @Test
    void unknownRefThrowsAndLeavesHeadUntouched() {
        AppConstants before = AppConstants.getInstance(loader);

        assertThrows(Exception.class, () -> loader.checkout("assistant/demo/does-not-exist"));

        assertEquals("main", loader.currentRef());
        // checkout failed before reaching super.reload() -- no eviction happened.
        assertSame(before, AppConstants.getInstance(loader));
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
            git.commit().setMessage("v3").call();
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
        recreated.configure(mock(IbisContext.class), "demo");
        loader = recreated; // let tearDown destroy this instance too

        assertEquals("assistant/demo/draft-abc123", recreated.currentRef());
        assertFalse(recreated.isDefaultRef());

        recreated.checkout("main");

        assertTrue(recreated.isDefaultRef());
    }
}
