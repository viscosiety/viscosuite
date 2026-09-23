package com.viscosiety.classloaders;

import java.io.File;
import java.nio.file.Path;

import org.frankframework.configuration.IbisContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class GitClassLoaderAccessorsTest {

	@TempDir Path tmp;
	private GitClassLoader loader;

	@BeforeEach
	void setUp() throws Exception {
		loader = TempGitRepo.configuredLoader(tmp, mock(IbisContext.class), "tenant");
	}

	@AfterEach
	void tearDown() {
		loader.destroy();
	}

	@Test
	void resourceDirIsCloneSubdir() {
		File expected = tmp.resolve(TempGitRepo.CLONE_DIR).resolve("ff-configurations/demo").toFile();
		assertEquals(expected.getAbsoluteFile(), loader.getResourceDir().getAbsoluteFile());
		assertTrue(loader.getResourceDir().isDirectory());
	}

	@Test
	void currentCommitIsHeadShaAndFollowsCheckout() throws Exception {
		String onMain = loader.currentCommit();
		assertNotNull(onMain);
		assertTrue(onMain.matches("[0-9a-f]{40}"), onMain);
		loader.checkout("assistant/demo/draft-abc123");
		String onDraft = loader.currentCommit();
		assertTrue(onDraft.matches("[0-9a-f]{40}"), onDraft);
		assertNotEquals(onMain, onDraft);
	}
}
