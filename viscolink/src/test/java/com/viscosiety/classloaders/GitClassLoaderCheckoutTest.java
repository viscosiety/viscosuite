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
        work = tmp.resolve("work").toFile();
        remote = tmp.resolve("origin.git").toFile();
        try (Git git = Git.init().setDirectory(work).setInitialBranch("main").call()) {
            File cfg = new File(work, "ff-configurations/demo/Configuration.xml");
            cfg.getParentFile().mkdirs();
            Files.writeString(cfg.toPath(), "<Configuration version=\"1\"/>");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("v1").call();
            git.checkout().setCreateBranch(true).setName("assistant/demo/draft-abc123").call();
            Files.writeString(cfg.toPath(), "<Configuration version=\"2\"/>");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("v2").call();
            git.checkout().setName("main").call();
        }
        Git.cloneRepository().setURI(work.toURI().toString()).setDirectory(remote).setBare(true).call().close();

        ibisContext = mock(IbisContext.class);
        loader = new GitClassLoader(getClass().getClassLoader());
        loader.setRepoUrl(remote.toURI().toString());
        loader.setRepoSubdir("ff-configurations/demo");
        loader.setLocalPath(tmp.resolve("clone").toString());
        loader.configure(ibisContext, "demo");
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
        // super.reload() evicted the cached AppConstants instance -- the next lookup is a new one.
        assertNotSame(before, AppConstants.getInstance(loader));
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
        assertNotSame(beforeSecondCheckout, AppConstants.getInstance(loader));
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
        assertThrows(IllegalArgumentException.class, () -> GitClassLoader.validateRef("-evil"));
        assertThrows(IllegalArgumentException.class, () -> GitClassLoader.validateRef("a b"));
        assertThrows(IllegalArgumentException.class, () -> GitClassLoader.validateRef(""));
        assertThrows(IllegalArgumentException.class, () -> GitClassLoader.validateRef(null));
        assertDoesNotThrow(() -> GitClassLoader.validateRef("assistant/demo/draft-abc123"));
        assertDoesNotThrow(() -> GitClassLoader.validateRef("main"));
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
}
