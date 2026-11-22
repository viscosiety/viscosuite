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

package org.frankframework.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins ViscoLink's temporary classpath override of the Frank!Framework {@link LocalFileSystem} (see that
 * file's banner, and frankframework/frankframework#12074): every folder and filename a configuration
 * reaches through a {@code LocalFileSystemPipe} or {@code LocalFileSystemSender} stays inside the
 * configured {@code root}.
 *
 * <p>Upstream's {@code toFile} does not enforce the root: an absolute filename skips it, and a relative
 * one is resolved against it without checking the result, so {@code ..} escapes. That matters because
 * {@code FileSystemActor.determineFilename} falls back to the input message when neither the
 * {@code filename} attribute nor the {@code filename} parameter is set, which puts the path under the
 * control of whoever sends the message.
 */
class LocalFileSystemRootContainmentTest {

	@TempDir
	Path base;

	private Path root;
	private Path outside;
	private LocalFileSystem fileSystem;

	@BeforeEach
	void setUp() throws Exception {
		root = Files.createDirectories(base.resolve("root"));
		outside = Files.createDirectories(base.resolve("outside"));

		fileSystem = new LocalFileSystem();
		fileSystem.setRoot(root.toAbsolutePath().toString());
	}

	private void open(LocalFileSystem fs) throws Exception {
		fs.configure();
		fs.open();
	}

	/**
	 * The point of the override over upstream's opt-in PR: nothing has to be switched on. The field
	 * default is the safe value and viscolink's DeploymentSpecifics.properties states it explicitly, so
	 * a fresh instance confines by itself. If the F!F release ever starts shipping
	 * {@code application.security.filesystem.allowPathsOutsideRoot=true} and that entry is dropped, this
	 * test fails.
	 */
	@Test
	void containmentIsOnWithoutAnyConfiguration() throws Exception {
		open(fileSystem);

		assertThrows(FileSystemException.class, () -> fileSystem.toFile("../outside/nope.txt"));
	}

	@Nested
	class WhenPathsOutsideRootAreRejected {

		@BeforeEach
		void setUp() throws Exception {
			fileSystem.setAllowPathsOutsideRoot(false);
			open(fileSystem);
		}

		@Test
		void absoluteFilenameOutsideRootIsRejected() {
			String filename = outside.resolve("nope.txt").toAbsolutePath().toString();

			FileSystemException e = assertThrows(FileSystemException.class, () -> fileSystem.toFile(filename));
			assertTrue(e.getMessage().contains("outside the root"), e.getMessage());
		}

		@Test
		void parentTraversalIsRejected() {
			assertThrows(FileSystemException.class, () -> fileSystem.toFile("../outside/nope.txt"));
		}

		/** The folder parameter is prepended to the filename, so it escapes just as well. */
		@Test
		void parentTraversalViaFolderParameterIsRejected() {
			assertThrows(FileSystemException.class, () -> fileSystem.toFile("../outside", "nope.txt"));
		}

		@Test
		void readOutsideRootIsRejected() throws Exception {
			Files.writeString(outside.resolve("secret.txt"), "credentials");

			assertThrows(FileSystemException.class, () -> fileSystem.toFile("../outside/secret.txt"));
		}

		@Test
		void relativeFilenameInsideRootIsAllowed() throws Exception {
			Path resolved = fileSystem.toFile("sub/file.txt");

			assertTrue(resolved.toAbsolutePath().normalize().startsWith(root));
		}

		@Test
		void absoluteFilenameInsideRootIsAllowed() throws Exception {
			Path resolved = fileSystem.toFile(root.resolve("file.txt").toAbsolutePath().toString());

			assertEquals(root.resolve("file.txt"), resolved);
		}

		@Test
		void traversalThatStaysInsideRootIsAllowed() throws Exception {
			Path resolved = fileSystem.toFile("sub/../file.txt");

			assertTrue(resolved.toAbsolutePath().normalize().startsWith(root));
		}

		@Test
		void writeInsideRootIsAllowed() throws Exception {
			Path resolved = fileSystem.toFile("allowed.txt");
			try (ByteArrayInputStream in = new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8))) {
				fileSystem.createFile(resolved, in);
			}

			assertEquals("payload", Files.readString(root.resolve("allowed.txt")));
		}

		/**
		 * Regression guard for the real-path fallback in {@code verifyWithinRoot}: a purely lexical check
		 * rejects valid paths when the root and the filename reach the same place through different
		 * symlinks, as /var and /private/var do on macOS, or a symlinked mount point does on Linux. The
		 * framework real-paths some derived names (rollover targets, {@code .tmp-} staging files,
		 * inProcessFolder and errorFolder moves) while {@code root} keeps the symlinked form. Dropping the
		 * fallback broke 19 existing framework tests.
		 */
		@Test
		void rootReachedThroughASymlinkStillContainsItsFiles() throws Exception {
			LocalFileSystem viaLink = fileSystemRootedAt(Files.createSymbolicLink(base.resolve("link-to-root"), root));

			Path resolved = viaLink.toFile(root.resolve("real-path-file.txt").toAbsolutePath().toString());

			assertEquals(root.resolve("real-path-file.txt"), resolved);
		}

		/** The fallback must not become an escape hatch of its own. */
		@Test
		void symlinkedRootStillRejectsPathsOutsideIt() throws Exception {
			LocalFileSystem viaLink = fileSystemRootedAt(Files.createSymbolicLink(base.resolve("link-to-root-2"), root));

			assertThrows(FileSystemException.class, () -> viaLink.toFile("../outside/nope.txt"));
		}

		/** Unchanged upstream behaviour: with no root there is no boundary to enforce. */
		@Test
		void withoutRootAbsolutePathsRemainAllowed() throws Exception {
			LocalFileSystem noRoot = new LocalFileSystem();
			noRoot.setAllowPathsOutsideRoot(false);
			open(noRoot);

			Path target = outside.resolve("no-root.txt").toAbsolutePath();

			assertEquals(target, noRoot.toFile(target.toString()));
		}

		private LocalFileSystem fileSystemRootedAt(Path newRoot) throws Exception {
			LocalFileSystem fs = new LocalFileSystem();
			fs.setRoot(newRoot.toAbsolutePath().toString());
			fs.setAllowPathsOutsideRoot(false);
			open(fs);
			return fs;
		}
	}

	/**
	 * The permissive side is upstream's behaviour, reachable here only through the escape hatch (a
	 * {@code -D} system property, or this setter). It is pinned so that a future re-derivation of the
	 * override from a newer F!F version cannot quietly change what the escape hatch means.
	 */
	@Nested
	class WhenPathsOutsideRootAreAllowed {

		@BeforeEach
		void setUp() throws Exception {
			fileSystem.setAllowPathsOutsideRoot(true);
			open(fileSystem);
		}

		@Test
		void absoluteFilenameStillEscapesRoot() throws Exception {
			Path target = outside.resolve("written-by-absolute-path.txt");

			Path resolved = fileSystem.toFile(target.toAbsolutePath().toString());
			try (ByteArrayInputStream in = new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8))) {
				fileSystem.createFile(resolved, in);
			}

			assertFalse(resolved.normalize().startsWith(root));
			assertEquals("payload", Files.readString(target));
		}

		@Test
		void parentTraversalStillEscapesRoot() throws Exception {
			Path resolved = fileSystem.toFile("../outside/written-by-traversal.txt");
			try (ByteArrayInputStream in = new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8))) {
				fileSystem.createFile(resolved, in);
			}

			assertTrue(Files.exists(outside.resolve("written-by-traversal.txt")));
		}

		@Test
		void readOutsideRootStillWorks() throws Exception {
			Files.writeString(outside.resolve("secret.txt"), "credentials");

			assertEquals("credentials", fileSystem.readFile(fileSystem.toFile("../outside/secret.txt"), null).asString());
		}
	}
}
