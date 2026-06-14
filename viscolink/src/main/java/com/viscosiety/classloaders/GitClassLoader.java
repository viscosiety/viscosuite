package com.viscosiety.classloaders;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import org.frankframework.configuration.ClassLoaderException;
import org.frankframework.configuration.IbisContext;
import org.frankframework.configuration.classloaders.AbstractClassLoader;
import org.frankframework.util.AppConstants;

/**
 * Loads a Frank!Framework configuration from a remote Git repository. A single
 * repository may host many configurations, each in its own subdirectory — set
 * {@code repoSubdir} to the subpath whose root holds that configuration's
 * {@code Configuration.xml} and resources.
 *
 * Configure via AppConstants:
 * <pre>
 * configurations.MyConfig.classLoaderType=com.viscosiety.classloaders.GitClassLoader
 * configurations.MyConfig.repoUrl=https://gitlab.com/org/my-repo.git
 * configurations.MyConfig.repoToken=glpat-XXXX
 * configurations.MyConfig.repoSubdir=ff-configurations/my-config   (optional)
 * configurations.MyConfig.localPath=/opt/frank/git-repos/my-config (optional)
 * </pre>
 *
 * Resource resolution root, in precedence order:
 * <ol>
 *   <li>{@code cloneRoot/<repoSubdir>} when {@code repoSubdir} is set — the full
 *       subpath to the configuration root within the repository (the F!F basePath /
 *       configuration name is <em>not</em> appended);</li>
 *   <li>{@code cloneRoot/<basePath>} otherwise, where basePath defaults to the
 *       configuration name (directory-of-configurations repository);</li>
 *   <li>{@code cloneRoot} when no basePath is set (single configuration at the root).</li>
 * </ol>
 *
 * On {@link #reload()} the classloader performs a {@code git pull}. If HEAD
 * has advanced, {@code super.reload()} clears AppConstants and signals F!F to
 * re-read the configuration. If the repository is already up-to-date the reload
 * is a no-op — no unnecessary configuration restart occurs.
 */
public class GitClassLoader extends AbstractClassLoader {

    private String repoUrl;
    private String repoUsername = "oauth2";
    private String repoToken = "";
    private String repoSubdir;
    private String localPath;

    private File localDir;
    private File resourceDir;

    public GitClassLoader(ClassLoader parent) {
        super(parent);
    }

    @Override
    public void configure(IbisContext ibisContext, String configurationName) throws ClassLoaderException {
        super.configure(ibisContext, configurationName);

        if (repoUrl == null || repoUrl.isBlank()) {
            throw new ClassLoaderException("repoUrl is required for GitClassLoader");
        }

        if (localPath == null || localPath.isBlank()) {
            localPath = "/opt/frank/git-repos/" + configurationName;
        }

        localDir = new File(localPath);

        try {
            cloneOrVerify();
        } catch (Exception e) {
            throw new ClassLoaderException("failed to initialise git repository for configuration [" + configurationName + "]", e);
        }

        // The clone target (localDir) is the repository root. Resolve the configuration root
        // within it: an explicit repoSubdir wins (full subpath, e.g. ff-configurations/my-config);
        // otherwise fall back to the F!F basePath (= configuration name, set by
        // AbstractClassLoader#configure) selecting a per-config subdirectory, mirroring the
        // DirectoryClassLoader "append basePath to root" pattern; otherwise the repository root
        // itself is the configuration root (single-config repository).
        if (repoSubdir != null && !repoSubdir.isBlank()) {
            resourceDir = new File(localDir, repoSubdir);
        } else if (getBasePath() != null) {
            resourceDir = new File(localDir, getBasePath());
        } else {
            resourceDir = localDir;
        }

        // Expose the checked-out git ref (branch name, or tag when HEAD is detached) as the F!F
        // configuration.version. Without this, configurations loaded from git have no BuildInfo.properties
        // and the framework logs "unable to determine [configuration.version]". The version is read from
        // this classloader's AppConstants by ConfigurationUtils#getConfigurationVersion during the
        // Configuration context refresh, which happens after configure(), so setting it here is in time.
        String gitVersion = resolveGitVersion();
        if (gitVersion != null) {
            AppConstants.getInstance(this).setProperty("configuration.version", gitVersion);
            log.info("[{}] configuration.version set to git ref [{}]", configurationName, gitVersion);
        }

        log.info("[{}] GitClassLoader ready — local clone at [{}], resources at [{}]", configurationName, localDir, resourceDir);
    }

    /**
     * The checked-out git ref to report as the configuration version: the branch name when HEAD is on a
     * branch (the normal clone-of-default-branch case), otherwise the nearest tag (detached HEAD checked
     * out at a tag), falling back to the short commit id. Returns null if the ref cannot be determined.
     */
    private String resolveGitVersion() {
        try (Git git = Git.open(localDir)) {
            Repository repo = git.getRepository();
            String fullBranch = repo.getFullBranch();
            if (fullBranch != null && fullBranch.startsWith("refs/heads/")) {
                return repo.getBranch();
            }
            String described = git.describe().setTags(true).call();
            return described != null ? described : repo.getBranch();
        } catch (Exception e) {
            log.warn("[{}] could not determine git ref for configuration.version", getConfigurationName(), e);
            return null;
        }
    }

    @Override
    protected boolean getAllowCustomClasses() {
        return false;
    }

    @Override
    public URL getLocalResource(String name) {
        File file = new File(resourceDir, name);
        if (file.exists()) {
            try {
                return file.toURI().toURL();
            } catch (MalformedURLException e) {
                log.error("could not create URL for resource [{}]", name, e);
            }
        }
        return null;
    }

    @Override
    public void reload() throws ClassLoaderException {
        try {
            boolean changed = pull();
            if (changed) {
                log.info("[{}] git pull produced changes — reloading configuration", getConfigurationName());
                super.reload();
            } else {
                log.debug("[{}] git pull: already up-to-date, skipping reload", getConfigurationName());
            }
        } catch (Exception e) {
            throw new ClassLoaderException("git pull failed for configuration [" + getConfigurationName() + "]", e);
        }
    }

    // --- property setters (auto-wired by ClassLoaderManager.applyConfigurationProperties) ---

    public void setRepoUrl(String repoUrl) {
        this.repoUrl = repoUrl;
    }

    /** Username for HTTPS authentication. Defaults to {@code oauth2} (GitLab deploy token convention). */
    public void setRepoUsername(String repoUsername) {
        this.repoUsername = repoUsername;
    }

    /** Personal access token or deploy token for HTTPS authentication. Leave blank for public repos. */
    public void setRepoToken(String repoToken) {
        this.repoToken = repoToken;
    }

    /**
     * Subpath within the repository whose root holds this configuration's {@code Configuration.xml}.
     * When set, it is the full configuration root (e.g. {@code ff-configurations/my-config}) and the
     * configuration name is not appended. Leave unset to fall back to basePath (= configuration name)
     * or, absent that, the repository root.
     */
    public void setRepoSubdir(String repoSubdir) {
        this.repoSubdir = repoSubdir;
    }

    /** Local directory for the git clone. Defaults to {@code /opt/frank/git-repos/<configName>}. */
    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    // --- internal ---

    private UsernamePasswordCredentialsProvider credentials() {
        if (repoToken == null || repoToken.isBlank()) return null;
        return new UsernamePasswordCredentialsProvider(repoUsername, repoToken);
    }

    private void cloneOrVerify() throws GitAPIException {
        if (new File(localDir, ".git").exists()) {
            // Refresh an existing clone so freshly-created classloaders (full reload, or a new
            // instance over a persisted clone) pick up upstream commits. A pull failure here is
            // non-fatal: keep serving the on-disk version rather than failing the configuration.
            try {
                boolean changed = pull();
                log.info("[{}] refreshed existing clone at [{}] (updated=[{}])", getConfigurationName(), localDir, changed);
            } catch (Exception e) {
                log.warn("[{}] could not pull latest into existing clone at [{}]; continuing with on-disk version", getConfigurationName(), localDir, e);
            }
            return;
        }
        log.info("[{}] cloning [{}] into [{}]", getConfigurationName(), repoUrl, localDir);
        var clone = Git.cloneRepository().setURI(repoUrl).setDirectory(localDir);
        var creds = credentials();
        if (creds != null) clone.setCredentialsProvider(creds);
        clone.call().close();
    }

    private boolean pull() throws Exception {
        try (Git git = Git.open(localDir)) {
            ObjectId before = git.getRepository().resolve("HEAD");
            var pull = git.pull();
            var creds = credentials();
            if (creds != null) pull.setCredentialsProvider(creds);
            pull.call();
            ObjectId after = git.getRepository().resolve("HEAD");
            return !before.equals(after);
        }
    }
}
