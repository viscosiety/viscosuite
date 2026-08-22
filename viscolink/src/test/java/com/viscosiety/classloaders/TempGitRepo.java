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

/**
 * Builds the bare "origin" + local clone fixture shared by {@link GitClassLoaderCheckoutTest} and
 * {@code org.frankframework.visco.security.ConfigRefServletTest}: a repository with a {@code main}
 * branch (Configuration.xml v1) and an {@code assistant/demo/draft-abc123} branch (v2), both under
 * {@code ff-configurations/demo}.
 */
public final class TempGitRepo {

	public static final String WORK_DIR = "work";
	public static final String REMOTE_DIR = "origin.git";
	public static final String CLONE_DIR = "clone";

	private static final String SUBDIR = "ff-configurations/demo";
	private static final String DRAFT_BRANCH = "assistant/demo/draft-abc123";

	private TempGitRepo() {
	}

	/**
	 * Builds the bare origin ({@code tmp/origin.git}, from a scratch checkout at {@code tmp/work})
	 * and returns a {@link GitClassLoader} already {@code configure()}d against a clone at
	 * {@code tmp/clone}, registered under {@code configurationName}.
	 */
	public static GitClassLoader configuredLoader(Path tmp, IbisContext ctx, String configurationName) throws Exception {
		File work = tmp.resolve(WORK_DIR).toFile();
		File remote = tmp.resolve(REMOTE_DIR).toFile();
		try (Git git = Git.init().setDirectory(work).setInitialBranch("main").call()) {
			File cfg = new File(work, SUBDIR + "/Configuration.xml");
			cfg.getParentFile().mkdirs();
			Files.writeString(cfg.toPath(), "<Configuration version=\"1\"/>");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("v1").call();
			git.checkout().setCreateBranch(true).setName(DRAFT_BRANCH).call();
			Files.writeString(cfg.toPath(), "<Configuration version=\"2\"/>");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("v2").call();
			git.checkout().setName("main").call();
		}
		Git.cloneRepository().setURI(work.toURI().toString()).setDirectory(remote).setBare(true).call().close();

		GitClassLoader loader = new GitClassLoader(TempGitRepo.class.getClassLoader());
		loader.setRepoUrl(remote.toURI().toString());
		loader.setRepoSubdir(SUBDIR);
		loader.setLocalPath(tmp.resolve(CLONE_DIR).toString());
		loader.configure(ctx, configurationName);
		return loader;
	}

	/**
	 * Adds a third branch, {@code assistant/demo/no-subdir}, branched from {@code main} with the
	 * configuration subdirectory removed, and pushes it to the bare origin so a fresh
	 * {@code checkout(ref)} can fetch it. {@code tmp} must already have gone through
	 * {@link #configuredLoader}, whose {@code work} checkout this reuses and leaves back on
	 * {@code main}. Returns the branch name.
	 */
	public static String addBranchWithoutSubdir(Path tmp) throws Exception {
		String branch = "assistant/demo/no-subdir";
		File work = tmp.resolve(WORK_DIR).toFile();
		File remote = tmp.resolve(REMOTE_DIR).toFile();
		try (Git git = Git.open(work)) {
			git.checkout().setCreateBranch(true).setName(branch).call();
			git.rm().addFilepattern(SUBDIR + "/Configuration.xml").call();
			git.commit().setMessage("remove configuration subdir").call();
			git.checkout().setName("main").call();
			git.push().setRemote(remote.toURI().toString())
					.setRefSpecs(new RefSpec("+" + branch + ":refs/heads/" + branch))
					.call();
		}
		return branch;
	}
}
