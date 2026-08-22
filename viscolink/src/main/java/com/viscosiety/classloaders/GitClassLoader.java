package com.viscosiety.classloaders;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
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
 *
 * {@link #checkout(String)} only moves the clone's HEAD to a different branch and clears this
 * classloader's cached {@code AppConstants}; the real configuration reload is
 * {@code IbisContext.reload(configurationName)}, dispatched by the ref servlet
 * ({@code org.frankframework.visco.security.ConfigRefServlet}) after {@code checkout} returns.
 * The static {@link #lookup(String)} registry exists because that servlet needs to reach a
 * running instance's classloader without depending on Spring bean visibility from its context.
 */
public class GitClassLoader extends AbstractClassLoader {

    /** Ref names accepted by {@link #checkout(String)}: plain git branch-name characters only. */
    private static final Pattern SAFE_REF = Pattern.compile("^[A-Za-z0-9._/-]+$");

    /**
     * Socket timeout (seconds) for every network git operation — clone, fetch, pull. JGit's
     * default is 0 = wait forever, and these calls run while the caller holds this classloader's
     * monitor (the ref servlet serialises checkout and reload on it): a GitLab that accepts the
     * connection and then goes silent would otherwise wedge every later checkout/reload on this
     * configuration for the lifetime of the process, with no way back short of a pod restart.
     */
    static final int GIT_NETWORK_TIMEOUT_SECONDS = 30;

    /**
     * Configuration name -&gt; live classloader. The ref servlet (org.frankframework.visco.security.
     * ConfigRefServlet) reaches the classloader through this registry rather than a Spring bean
     * lookup: whether IbisManager is visible from the servlet's root WebApplicationContext depends
     * on the console/app context topology, while a static map has no such dependency.
     */
    private static final Map<String, GitClassLoader> REGISTRY = new ConcurrentHashMap<>();

    private String repoUrl;
    private String repoUsername = "oauth2";
    private String repoToken = "";
    private String repoSubdir;
    private String localPath;

    private File localDir;
    private File resourceDir;

    /** Branch name the clone started on (origin's HEAD); what "return to main" means for this instance. */
    private String defaultRef;

    public GitClassLoader(ClassLoader parent) {
        super(parent);
    }

    /** The registered classloader for a configuration, or null when none (non-git configuration or not yet configured). */
    public static GitClassLoader lookup(String configurationName) {
        return configurationName == null ? null : REGISTRY.get(configurationName);
    }

    /**
     * Rejects anything that is not a plain branch name: git would refuse most of these too, but
     * the servlet accepts the value from the network and must never pass it to a shell-like path.
     *
     * <p>Note what is deliberately <em>not</em> rejected here: fully-qualified forms like
     * {@code refs/heads/x} and {@code origin/x}. They are not traversal risks — {@link
     * #checkout(String)} prefixes the remote name itself, so they simply fail to resolve and come
     * back as a 409 "does not exist on the remote", which is the more useful answer than a 400.</p>
     */
    public static void validateRef(String ref) {
        if (ref == null || ref.isBlank() || !SAFE_REF.matcher(ref).matches() || ref.startsWith("-")) {
            throw new IllegalArgumentException("invalid git ref [" + ref + "]");
        }
        // The literal HEAD is a symbolic ref, never a branch: accepting it would make "switch to
        // HEAD" mean "switch to whatever is already checked out", a silent no-op the caller reads
        // as a successful switch.
        if (Constants.HEAD.equals(ref)) {
            throw new IllegalArgumentException("invalid git ref [" + ref + "]");
        }
        // ".." anywhere, not just as a whole path component: git's own check-ref-format forbids
        // the sequence outright (it is range syntax), and a per-segment test would let "a..b"
        // through while catching "a/../b".
        if (ref.contains("..")) {
            throw new IllegalArgumentException("invalid git ref [" + ref + "]");
        }
        for (String segment : ref.split("/")) {
            // No empty component, and no component ending in ".lock" -- that suffix names git's
            // own ref lockfiles, so git refuses to create such a branch in the first place.
            if (segment.isEmpty() || segment.endsWith(".lock")) {
                throw new IllegalArgumentException("invalid git ref [" + ref + "]");
            }
        }
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

        defaultRef = readDefaultRef();
        if (defaultRef == null) {
            // Pre-existing clone from before visco.defaultRef was introduced: best effort, only
            // correct as long as this configure() call is not itself running on a draft branch.
            defaultRef = resolveGitVersion();
        }
        REGISTRY.put(configurationName, this);
    }

    /**
     * Reads the default branch persisted in the clone's git config ({@code visco.defaultRef},
     * written once in {@link #cloneOrVerify()} right after the initial clone). Unlike
     * {@link #resolveGitVersion()} this is stable across classloader recreation: F!F's real
     * reload path destroys this instance and builds a new one whose {@code configure()} runs
     * against whatever branch the clone happens to be on at that moment (e.g. a draft branch
     * left checked out by a prior {@link #checkout(String)}) -- resolving "default" from the
     * currently checked-out branch at that point would silently redefine it as the draft.
     */
    private String readDefaultRef() {
        try (Git git = Git.open(localDir)) {
            return git.getRepository().getConfig().getString("visco", null, "defaultRef");
        } catch (Exception e) {
            log.warn("[{}] could not read visco.defaultRef from git config", getConfigurationName(), e);
            return null;
        }
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
    public synchronized void reload() throws ClassLoaderException {
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

    /**
     * Switches the clone to {@code ref} (a remote branch) and reloads the configuration
     * unconditionally. The pull-based {@link #reload()} cannot be reused here: it only reloads
     * when HEAD advanced during the pull, and right after a checkout the pull reports "already
     * up to date". Fetch/checkout failures leave the clone on its previous ref.
     */
    public synchronized void checkout(String ref) throws ClassLoaderException {
        validateRef(ref);
        try (Git git = Git.open(localDir)) {
            var fetch = git.fetch().setRemote(Constants.DEFAULT_REMOTE_NAME).setTimeout(GIT_NETWORK_TIMEOUT_SECONDS);
            var creds = credentials();
            if (creds != null) fetch.setCredentialsProvider(creds);
            fetch.call();
            String remoteRef = Constants.DEFAULT_REMOTE_NAME + "/" + ref;
            if (git.getRepository().resolve(Constants.R_REMOTES + remoteRef) == null) {
                throw new ClassLoaderException("branch [" + ref + "] does not exist on the remote");
            }
            boolean localExists = git.getRepository().resolve(Constants.R_HEADS + ref) != null;
            boolean isCurrent = ref.equals(git.getRepository().getBranch());
            if (localExists && !isCurrent) {
                // Re-point an existing local branch at the remote tip rather than keeping a stale one.
                git.branchCreate().setName(ref).setStartPoint(remoteRef).setForce(true)
                        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM).call();
            }
            if (!localExists) {
                git.checkout().setName(ref).setCreateBranch(true).setStartPoint(remoteRef)
                        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM).call();
            } else if (isCurrent) {
                // JGit refuses to force-move the branch that is currently checked out via
                // branchCreate(); reset the checked-out branch itself to the remote tip instead.
                git.reset().setMode(ResetType.HARD).setRef(remoteRef).call();
            } else {
                git.checkout().setName(ref).call();
            }
            log.info("[{}] checked out ref [{}] -- reloading configuration", getConfigurationName(), ref);
        } catch (ClassLoaderException e) {
            throw e;
        } catch (Exception e) {
            throw new ClassLoaderException("git checkout [" + ref + "] failed for configuration [" + getConfigurationName() + "]", e);
        }
        // super.reload() evicts the cached AppConstants instance first; set configuration.version
        // on the fresh instance created afterwards, not the one about to be thrown away.
        super.reload();
        String gitVersion = resolveGitVersion();
        if (gitVersion != null) {
            AppConstants.getInstance(this).setProperty("configuration.version", gitVersion);
        }
    }

    /** The currently checked-out branch name (falls back to the described tag/commit when detached). */
    public String currentRef() {
        return resolveGitVersion();
    }

    /** True when the clone is on the branch it was originally cloned from. */
    public boolean isDefaultRef() {
        String current = currentRef();
        return current != null && current.equals(defaultRef);
    }

    /**
     * True when the currently checked-out ref actually contains this configuration's resource
     * root. A branch created for an unrelated purpose (or one that predates the configuration
     * being added to the repository) can be checked out successfully -- {@link #checkout(String)}
     * only moves HEAD -- while leaving {@code resourceDir} missing; the ref servlet
     * ({@code org.frankframework.visco.security.ConfigRefServlet}) checks this after checkout and
     * reverts rather than dispatching a reload against a configuration with no resources.
     */
    public boolean resourceDirExists() {
        return resourceDir != null && resourceDir.isDirectory();
    }

    /** The configured {@code repoSubdir}, or null when unset (see class javadoc for the fallback chain). */
    public String getRepoSubdir() {
        return repoSubdir;
    }

    @Override
    public void destroy() {
        if (getConfigurationName() != null) {
            REGISTRY.remove(getConfigurationName(), this);
        }
        super.destroy();
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

    private void cloneOrVerify() throws Exception {
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
        var clone = Git.cloneRepository().setURI(repoUrl).setDirectory(localDir).setTimeout(GIT_NETWORK_TIMEOUT_SECONDS);
        var creds = credentials();
        if (creds != null) clone.setCredentialsProvider(creds);
        try (Git git = clone.call()) {
            // Record the branch this clone started on so a later classloader recreation (F!F's
            // real reload path) can still tell "default" from "draft" even when it runs
            // configure() while the clone happens to be checked out on a draft branch.
            String ref = resolveGitVersion();
            if (ref != null) {
                StoredConfig config = git.getRepository().getConfig();
                config.setString("visco", null, "defaultRef", ref);
                config.save();
            }
        }
    }

    private boolean pull() throws Exception {
        try (Git git = Git.open(localDir)) {
            ObjectId before = git.getRepository().resolve(Constants.HEAD);
            var pull = git.pull().setTimeout(GIT_NETWORK_TIMEOUT_SECONDS)
                    // FF_ONLY: this clone is a read-only mirror of the branch, so the only
                    // legitimate pull is a fast-forward. A force-pushed (rewritten) branch would
                    // otherwise be silently *merged* into the local one, leaving the instance
                    // running a commit that exists nowhere upstream. Failing is the honest
                    // outcome -- non-fatal at clone-refresh time (cloneOrVerify keeps serving the
                    // on-disk version), an exception out of reload() otherwise. Recovery is a
                    // checkout of the branch, which hard-resets to the remote tip.
                    .setFastForward(MergeCommand.FastForwardMode.FF_ONLY);
            var creds = credentials();
            if (creds != null) pull.setCredentialsProvider(creds);
            PullResult result = pull.call();
            if (!result.isSuccessful()) {
                // JGit reports a refused FF_ONLY merge in the result rather than throwing.
                throw new ClassLoaderException("git pull was not a fast-forward for configuration ["
                        + getConfigurationName() + "] (branch rewritten upstream?): " + result);
            }
            ObjectId after = git.getRepository().resolve(Constants.HEAD);
            // before == null is an unborn HEAD (empty clone): anything the pull brought in is a
            // change, and null.equals() would NPE.
            return before == null || !before.equals(after);
        }
    }
}
