package hudson.plugins.mercurial;

import static java.util.logging.Level.FINE;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.ProcStarter;
import hudson.Util;
import hudson.matrix.MatrixRun;
import hudson.model.*;
import hudson.plugins.mercurial.browser.HgBrowser;
import hudson.plugins.mercurial.browser.HgWeb;
import hudson.scm.ChangeLogParser;
import hudson.scm.PollingResult;
import hudson.scm.PollingResult.Change;
import hudson.scm.SCMDescriptor;
import hudson.scm.SCMRevisionState;
import hudson.scm.SCM;
import hudson.util.ArgumentListBuilder;
import hudson.util.ForkOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sf.json.JSONObject;

import org.apache.commons.io.output.NullOutputStream;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.SuppressWarnings;
import hudson.slaves.NodeProperty;
import hudson.util.DescribableList;
import java.util.HashMap;
import java.util.logging.Level;

/**
 * Mercurial SCM.
 */
public class MercurialSCM extends SCM implements Serializable {
    // old fields are left so that old config data can be read in, but
    // they are deprecated. transient so that they won't show up in XML
    // when writing back
    @Deprecated
    private transient boolean forest;

    /**
     * Name of selected installation, if any.
     */
    private final String installation;

    /**
     * Source repository URL from which we pull.
     */
    private final String source;

    /**
     * Prefixes of files within the repository which we're dependent on.
     * Storing as member variable so as to only parse the dependencies string once.
     * Will be either null (use whole repo), or nonempty list of subdir names.
     */
    private transient Set<String> _modules;
    // Same thing, but not parsed for jelly.
    private final String modules;

    /**
     * In-repository branch to follow. Null indicates "default".
     */
    private final String branch;

    /** Slash-separated subdirectory of the workspace in which the repository will be kept; null for top level. */
    private final String subdir;

    private final boolean clean;

    private HgBrowser browser;

    @DataBoundConstructor
    public MercurialSCM(String installation, String source, String branch, String modules, String subdir, HgBrowser browser, boolean clean) {
        this.installation = installation;
        this.source = Util.fixEmptyAndTrim(source);
        this.modules = Util.fixNull(modules);
        this.subdir = Util.fixEmptyAndTrim(subdir);
        this.clean = clean;
        parseModules();
        branch = Util.fixEmpty(branch);
        if (branch != null && branch.equals("default")) {
            branch = null;
        }
        this.branch = branch;
        this.browser = browser;
    }

    private void parseModules() {
        if (modules.trim().length() > 0) {
            _modules = new HashSet<String>();
            // split by commas and whitespace, except "\ "
            for (String r : modules.split("(?<!\\\\)[ \\r\\n,]+")) {
                if (r.length() == 0) { // initial spaces should be ignored
                    continue;
                }
                // now replace "\ " to " ".
                r = r.replaceAll("\\\\ ", " ");
                // Strip leading slashes
                while (r.startsWith("/")) {
                    r = r.substring(1);
                }
                // Use unix file path separators
                r = r.replace('\\', '/');
                _modules.add(r);
            }
        } else {
            _modules = null;
        }
    }

    private Object readResolve() {
        parseModules();
        return this;
    }

    public String getInstallation() {
        return installation;
    }

    /**
     * Gets the source repository path.
     * Either URL or local file path.
     */
    public String getSource() {
        return source;
    }
    private String getSource(EnvVars env) {
        return env.expand(source);
    }

    /**
     * In-repository branch to follow. Never null.
     */
    public String getBranch() {
        return branch == null ? "default" : branch;
    }

    /**
     * Same as {@link #getBranch()} but with <em>default</em> values of parameters expanded.
     */
    private String getBranchExpanded(AbstractProject<?,?> project) {
        EnvVars env = new EnvVars();
        ParametersDefinitionProperty params = project.getProperty(ParametersDefinitionProperty.class);
        if (params != null) {
            for (ParameterDefinition param : params.getParameterDefinitions()) {
                if (param instanceof StringParameterDefinition) {
                    String dflt = ((StringParameterDefinition) param).getDefaultValue();
                    if (dflt != null) {
                        env.put(param.getName(), dflt);
                    }
                }
            }
        }
        return getBranch(env);
    }

    private String getBranch(EnvVars env) {
        return branch == null ? "default" : env.expand(branch);
    }

    private String getSubdir(EnvVars env) {
        return env.expand( subdir );
    }
    
    public String getSubdir( ) {
        return subdir;
    }

    private FilePath workspace2Repo(FilePath workspace, EnvVars env) {
        return getSubdir(env) != null ? workspace.child(getSubdir(env)) : workspace;
    }
    
    public static EnvVars getPollEnvironment(AbstractProject p, FilePath ws, Launcher launcher, TaskListener listener)
        throws IOException, InterruptedException {
        return getPollEnvironment(p, ws, launcher, listener, true);
    }


    /**
     * An attempt to generate at least semi-useful EnvVars for polling calls, based on previous build.
     * Cribbed from various places.
     */
    public static EnvVars getPollEnvironment(AbstractProject p, FilePath ws, Launcher launcher, TaskListener listener, boolean reuseLastBuildEnv)
        throws IOException,InterruptedException {
        EnvVars env;
        StreamBuildListener buildListener = new StreamBuildListener((PrintStream)listener.getLogger());
        AbstractBuild b = (AbstractBuild)p.getLastBuild();

        if (reuseLastBuildEnv && b != null) {
            Node lastBuiltOn = b.getBuiltOn();

            if (lastBuiltOn != null) {
                env = lastBuiltOn.toComputer().getEnvironment().overrideAll(b.getCharacteristicEnvVars());
                for (NodeProperty nodeProperty: lastBuiltOn.getNodeProperties()) {
                    Environment environment = nodeProperty.setUp(b, launcher, (BuildListener)buildListener);
                    if (environment != null) {
                        environment.buildEnvVars(env);
                    }
                }
            } else {
                env = new EnvVars(System.getenv());
            }
            
            p.getScm().buildEnvVars(b,env);

            if (lastBuiltOn != null) {

            }

        } else {
            env = new EnvVars(System.getenv());
        }

        String rootUrl = Hudson.getInstance().getRootUrl();
        if(rootUrl!=null) {
            env.put("HUDSON_URL", rootUrl); // Legacy.
            env.put("JENKINS_URL", rootUrl);
            if( b != null) env.put("BUILD_URL", rootUrl+b.getUrl());
            env.put("JOB_URL", rootUrl+p.getUrl());
        }

        if(!env.containsKey("HUDSON_HOME")) // Legacy
            env.put("HUDSON_HOME", Hudson.getInstance().getRootDir().getPath() );

        if(!env.containsKey("JENKINS_HOME"))
            env.put("JENKINS_HOME", Hudson.getInstance().getRootDir().getPath() );

        if (ws != null)
            env.put("WORKSPACE", ws.getRemote());

        for (NodeProperty nodeProperty: Hudson.getInstance().getGlobalNodeProperties()) {
            Environment environment = nodeProperty.setUp(b, launcher, (BuildListener)buildListener);
            if (environment != null) {
                environment.buildEnvVars(env);
            }
        }

        EnvVars.resolve(env);

        return env;
    }

    @Override
    @SuppressWarnings("DLS_DEAD_LOCAL_STORE")
    public HgBrowser getBrowser() {
        if (browser == null) {
            try {
                return new HgWeb(getSource( )); // #2406
            } catch (MalformedURLException x) {
                // forget it
            }
        }
        return browser;
    }

    /**
     * True if we want clean check out each time. This means deleting everything in the repository checkout
     * (except <tt>.hg</tt>)
     */
    public boolean isClean() {
        return clean;
    }

    private ArgumentListBuilder findHgExe(AbstractBuild<?,?> build, TaskListener listener, boolean allowDebug) throws IOException, InterruptedException {
        return findHgExe(build.getBuiltOn(), listener, allowDebug);
    }

    /**
     * @param allowDebug
     *      If the caller intends to parse the stdout from Mercurial, pass in false to indicate
     *      that the optional --debug option shall never be activated.
     */
    ArgumentListBuilder findHgExe(Node node, TaskListener listener, boolean allowDebug) throws IOException, InterruptedException {
        for (MercurialInstallation inst : MercurialInstallation.allInstallations()) {
            if (inst.getName().equals(installation)) {
                // TODO what about forEnvironment?
                ArgumentListBuilder b = new ArgumentListBuilder(inst.executableWithSubstitution(
                        inst.forNode(node, listener).getHome()));
                if (allowDebug && inst.getDebug()) {
                    b.add("--debug");
                }
                return b;
            }
        }
        return new ArgumentListBuilder(getDescriptor().getHgExe());
    }

    static ProcStarter launch(Launcher launcher) {
        return launcher.launch().envs(Collections.singletonMap("HGPLAIN", "true"));
    }

    @Override
    public SCMRevisionState calcRevisionsFromBuild(AbstractBuild<?, ?> build, Launcher launcher, TaskListener listener)
            throws IOException, InterruptedException {
        EnvVars env = build.getEnvironment( listener );
        // tag action is added during checkout, so this shouldn't be called, but just in case.
        HgExe hg = new HgExe(this, launcher, build, listener);
        String tip = hg.tip(workspace2Repo(build.getWorkspace(), env), null);
        String rev = hg.tipNumber(workspace2Repo(build.getWorkspace(), env), null);
        return tip != null && rev != null ? new MercurialTagAction(tip, rev, getSubdir(env)) : null;
    }

    @Override
    public boolean requiresWorkspaceForPolling() {
        MercurialInstallation mercurialInstallation = findInstallation(installation);
        return mercurialInstallation == null || !(mercurialInstallation.isUseCaches() || mercurialInstallation.isUseSharing() );
    }

    @Override
    protected PollingResult compareRemoteRevisionWith(AbstractProject<?, ?> project, Launcher launcher, FilePath workspace,
            TaskListener listener, SCMRevisionState _baseline) throws IOException, InterruptedException {
        MercurialTagAction baseline = (MercurialTagAction)_baseline;

        PrintStream output = listener.getLogger();

        if (!requiresWorkspaceForPolling()) {
            launcher = Hudson.getInstance().createLauncher(listener);
            PossiblyCachedRepo possiblyCachedRepo = cachedSource(Hudson.getInstance(), launcher, listener, true);
            if (possiblyCachedRepo == null) {
                throw new IOException("Could not use cache to poll for changes. See error messages above for more details");
            }
            FilePath repositoryCache = new FilePath(new File(possiblyCachedRepo.getRepoLocation()));
            return compare(launcher, listener, baseline, output, Hudson.getInstance(), repositoryCache, project);
        }
        // TODO do canUpdate check similar to in checkout, and possibly return INCOMPARABLE

        try {
            EnvVars env = MercurialSCM.getPollEnvironment( project, workspace, launcher, listener );
            
            // Get the list of changed files.
            Node node = project.getLastBuiltOn(); // JENKINS-5984: ugly but matches what AbstractProject.poll uses; though compare JENKINS-14247
            FilePath repository = workspace2Repo(workspace, env);

            pull(launcher, repository, listener, output, node, getBranchExpanded(project));

            return compare(launcher, listener, baseline, output, node, repository, project);
        } catch(IOException e) {
            if (causedByMissingHg(e)) {
                listener.error(Messages.MercurialSCM_failed_to_compare_with_remote_repository());
                throw new AbortException("Failed to compare with remote repository");
            }
            IOException ex = new IOException("Failed to compare with remote repository");
            ex.initCause(e);
            throw ex;
        }
    }

    private PollingResult compare(Launcher launcher, TaskListener listener, MercurialTagAction baseline, PrintStream output, Node node, FilePath repository, AbstractProject<?,?> project) throws IOException, InterruptedException {
        EnvVars env = MercurialSCM.getPollEnvironment( project, null, launcher, listener );
        HgExe hg = new HgExe(this, launcher, node, listener, env);
        String _branch = getBranchExpanded(project);
        String remote = hg.tip(repository, _branch);
        String rev = hg.tipNumber(repository, _branch);
        if (remote == null) {
            throw new IOException("failed to find ID of branch head");
        }
        if (rev == null) {
            throw new IOException("failed to find revision of branch head");
        }
        if (remote.equals(baseline.id)) { // shortcut
            return new PollingResult(baseline, new MercurialTagAction(remote, rev, getSubdir(env)), Change.NONE);
        }
        Set<String> changedFileNames = parseStatus(hg.popen(repository, listener, false, new ArgumentListBuilder("status", "--rev", baseline.id, "--rev", remote)));

        MercurialTagAction cur = new MercurialTagAction(remote, rev, getSubdir(env));
        return new PollingResult(baseline,cur,computeDegreeOfChanges(changedFileNames,output));
    }

    static Set<String> parseStatus(String status) {
        Set<String> result = new HashSet<String>();
        Matcher m = Pattern.compile("(?m)^[ARM] (.+)").matcher(status);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }

    private void pull(Launcher launcher, FilePath repository, TaskListener listener, PrintStream output, Node node, String branch) throws IOException, InterruptedException {
        ArgumentListBuilder cmd = findHgExe(node, listener, true);
        cmd.add("pull");
        cmd.add("--rev", branch);
        PossiblyCachedRepo cachedSource = cachedSource(node, launcher, listener, true);
        if (cachedSource != null) {
            cmd.add(cachedSource.getRepoLocation());
        }
        joinWithPossibleTimeout(
                launch(launcher).cmds(cmd).stdout(output).pwd(repository),
                true, listener);
    }

    static int joinWithPossibleTimeout(ProcStarter proc, boolean useTimeout, final TaskListener listener) throws IOException, InterruptedException {
        return useTimeout ? proc.start().joinWithTimeout(/* #4528: not in JDK 5: 1, TimeUnit.HOURS*/60 * 60, TimeUnit.SECONDS, listener) : proc.join();
    }

    private Change computeDegreeOfChanges(Set<String> changedFileNames, PrintStream output) {
        LOGGER.log(FINE, "Changed file names: {0}", changedFileNames);

        if (changedFileNames.isEmpty()) {
            return Change.NONE;
        }

        Set<String> depchanges = dependentChanges(changedFileNames);
        LOGGER.log(FINE, "Dependent changed file names: {0}", depchanges);

        if (depchanges.isEmpty()) {
            output.println(Messages.MercurialSCM_non_dependent_changes_detected());
            return Change.INSIGNIFICANT;
        }

        output.println(Messages.MercurialSCM_dependent_changes_detected());
        return Change.SIGNIFICANT;
    }

    /**
     * Filter out the given file name list by picking up changes that are in the modules we care about.
     */
    private Set<String> dependentChanges(Set<String> changedFileNames) {
        Set<String> affecting = new HashSet<String>();

        for (String changedFile : changedFileNames) {
            if (changedFile.matches("[.]hg(ignore|tags)")) {
                continue;
            }
            if (_modules == null) {
                affecting.add(changedFile);
                continue;
            }
            String unixChangedFile = changedFile.replace('\\', '/');
            for (String dependency : _modules) {
                if (unixChangedFile.startsWith(dependency)) {
                    affecting.add(changedFile);
                    break;
                }
            }
        }

        return affecting;
    }

    public static MercurialInstallation findInstallation(String name) {
        for (MercurialInstallation inst : MercurialInstallation.allInstallations()) {
            if (inst.getName().equals(name)) {
                return inst;
            }
        }
        return null;
    }

    @Override
    public boolean checkout(AbstractBuild<?,?> build, Launcher launcher, FilePath workspace, final BuildListener listener, File changelogFile)
            throws IOException, InterruptedException {

        MercurialInstallation mercurialInstallation = findInstallation(installation);
        final boolean jobShouldUseSharing = mercurialInstallation != null && mercurialInstallation.isUseSharing();

        EnvVars env = build.getEnvironment(listener);
        
        FilePath repository = workspace2Repo(workspace, env);
        boolean canReuseExistingWorkspace;
        try {
            canReuseExistingWorkspace = canReuseWorkspace(repository, jobShouldUseSharing, build, launcher, listener);
        } catch(IOException e) {
            if (causedByMissingHg(e)) {
                listener.error("Failed to determine whether workspace can be reused because hg could not be found;" +
                        " check that you've properly configured your Mercurial installation");
            } else {
                e.printStackTrace(listener.error("Failed to determine whether workspace can be reused"));
            }
            throw new AbortException("Failed to determine whether workspace can be reused");
        }

        String revToBuild = getRevToBuild(build, build.getEnvironment(listener));
        if (canReuseExistingWorkspace) {
            update(build, launcher, repository, listener, revToBuild);
        } else {
            clone(build, launcher, repository, listener, revToBuild);
        }

        try {
            determineChanges(build, launcher, listener, changelogFile, repository, revToBuild);
        } catch (IOException e) {
            listener.error("Failed to capture change log");
            e.printStackTrace(listener.getLogger());
            throw new AbortException("Failed to capture change log");
        }
        return true;
    }
    
    private boolean canReuseWorkspace(FilePath repo,
            boolean jobShouldUseSharing, AbstractBuild<?,?> build,
            Launcher launcher, BuildListener listener)
                throws IOException, InterruptedException {
        if (!new FilePath(repo, ".hg/hgrc").exists()) {
            return false;
        }

        boolean jobUsesSharing = new FilePath(repo, ".hg/sharedpath").exists();
        if (jobShouldUseSharing != jobUsesSharing) {
            return false;
        }
        
        EnvVars env = build.getEnvironment(listener);
        
        HgExe hg = new HgExe(this,launcher,build,listener);
        String upstream = hg.config(repo, "paths.default");
        if (upstream == null) {
            return false;
        }
        String source = getSource( env );
        if (HgExe.pathEquals(source, upstream)) {
            return true;
        }
        listener.error(
                "Workspace reports paths.default as " + upstream +
                "\nwhich looks different than " + source +
                "\nso falling back to fresh clone rather than incremental update");
        return false;
    }

    private void determineChanges(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener, File changelogFile, FilePath repository, String revToBuild) throws IOException, InterruptedException {
        AbstractBuild<?, ?> previousBuild = build.getPreviousBuild();
        EnvVars env = build.getEnvironment(listener);
        MercurialTagAction prevTag = previousBuild != null ? findTag(previousBuild, env) : null;
        if (prevTag == null) {
            listener.getLogger().println("WARN: Revision data for previous build unavailable; unable to determine change log");
            createEmptyChangeLog(changelogFile, listener, "changelog");
            return;
        }

        ArgumentListBuilder logCommand = findHgExe(build, listener, true).add("log", "--rev", prevTag.getId());
        int exitCode = launch(launcher).cmds(logCommand).envs(env).pwd(repository).join();
        if(exitCode != 0) {
            listener.error("Previously built revision " + prevTag.getId() + " is not known in this clone; unable to determine change log");
            createEmptyChangeLog(changelogFile, listener, "changelog");
            return;
        }
        
        // calc changelog
        final FileOutputStream os = new FileOutputStream(changelogFile);
        try {
            os.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n".getBytes("UTF-8"));
            try {
                os.write("<changesets>\n".getBytes("UTF-8"));
                ArgumentListBuilder args = findHgExe(build, listener, false);
                args.add("log");
                args.add("--template", MercurialChangeSet.CHANGELOG_TEMPLATE);
                args.add("--rev", revToBuild + ":0");
                args.add("--follow");
                args.add("--prune", prevTag.getId());
                args.add("--encoding", "UTF-8");
                args.add("--encodingmode", "replace");

                ByteArrayOutputStream errorLog = new ByteArrayOutputStream();

                int r = launch(launcher).cmds(args).envs(env).stdout(new ForkOutputStream(os, errorLog)).pwd(repository).join();
                if(r!=0) {
                    Util.copyStream(new ByteArrayInputStream(errorLog.toByteArray()), listener.getLogger());
                    throw new IOException("Failure detected while running hg log to determine change log");
                }
            } finally {
                os.write("</changesets>".getBytes("UTF-8"));
            }
        } finally {
            os.close();
        }
    }

    private void update(AbstractBuild<?, ?> build, Launcher launcher, FilePath repository, BuildListener listener, String toRevision)
            throws IOException, InterruptedException {
        HgExe hg = new HgExe(this, launcher, build, listener);
        Node node = Computer.currentComputer().getNode(); // TODO why not build.getBuiltOn()?
        try {
            pull(launcher, repository, listener, new PrintStream(new NullOutputStream()), node, toRevision);
        } catch (IOException e) {
            if (causedByMissingHg(e)) {
                listener.error("Failed to pull because hg could not be found;" +
                        " check that you've properly configured your Mercurial installation");
            } else {
                e.printStackTrace(listener.error("Failed to pull"));
            }
            throw new AbortException("Failed to pull");
        }

        int updateExitCode;
        try {
            updateExitCode = hg.run("update", "--clean", "--rev", toRevision).pwd(repository).join();
        } catch (IOException e) {
            listener.error("Failed to update");
            e.printStackTrace(listener.getLogger());
            throw new AbortException("Failed to update");
        }
        if (updateExitCode != 0) {
            listener.error("Failed to update");
            throw new AbortException("Failed to update");
        }
        if (build.getNumber() % 100 == 0) {
            PossiblyCachedRepo cachedSource = cachedSource(node, launcher, listener, true);
            if (cachedSource != null && !cachedSource.isUseSharing()) {
                // Periodically recreate hardlinks to the cache to save disk space.
                hg.run("--config", "extensions.relink=", "relink", cachedSource.getRepoLocation()).pwd(repository).join(); // ignore failures
            }
        }

        if(clean) {
            if (hg.cleanAll().pwd(repository).join() != 0) {
                listener.error("Failed to clean unversioned files");
                throw new AbortException("Failed to clean unversioned files");
            }
        }

        String tip = hg.tip(repository, null);
        String rev = hg.tipNumber(repository, null);
        if (tip != null && rev != null) {
            EnvVars env = build.getEnvironment(listener);
            build.addAction(new MercurialTagAction(tip, rev, getSubdir(env)));
        }
    }

    private String getRevToBuild(AbstractBuild<?, ?> build, EnvVars env) {
        String revToBuild = getBranch(env);
        if (build instanceof MatrixRun) {
            MatrixRun matrixRun = (MatrixRun) build;
            MercurialTagAction parentRevision = matrixRun.getParentBuild().getAction(MercurialTagAction.class);
            if (parentRevision != null && parentRevision.getId() != null) {
                revToBuild = parentRevision.getId();
            }
        }
        return revToBuild;
    }

    /**
     * Start from scratch and clone the whole repository.
     */
    private void clone(AbstractBuild<?, ?> build, Launcher launcher, FilePath repository, BuildListener listener, String toRevision)
            throws InterruptedException, IOException {
        try {
            repository.deleteRecursive();
        } catch (IOException e) {
            e.printStackTrace(listener.error("Failed to clean the repository checkout"));
            throw new AbortException("Failed to clean the repository checkout");
        }

        EnvVars env = build.getEnvironment(listener);
        HgExe hg = new HgExe(this,launcher,build.getBuiltOn(),listener,env);

        ArgumentListBuilder args = new ArgumentListBuilder();
        PossiblyCachedRepo cachedSource = cachedSource(build.getBuiltOn(), launcher, listener, false);
        if (cachedSource != null) {
            if (cachedSource.isUseSharing()) {
                args.add("--config", "extensions.share=");
                args.add("share");
                args.add("--noupdate");
                args.add(cachedSource.getRepoLocation());
            } else {
                args.add("clone");
                args.add("--rev", toRevision);
                args.add("--noupdate");
                args.add(cachedSource.getRepoLocation());
            }
        } else {
            args.add("clone");
            args.add("--rev", toRevision);
            args.add("--noupdate");
            args.add(getSource( env ));
        }
        args.add(repository.getRemote());
        int cloneExitCode;
        try {
            cloneExitCode = hg.run(args).join();
        } catch (IOException e) {
            if (causedByMissingHg(e)) {
                listener.error("Failed to clone " + getSource( env ) + " because hg could not be found;" +
                        " check that you've properly configured your Mercurial installation");
            } else {
                e.printStackTrace(listener.error(Messages.MercurialSCM_failed_to_clone(getSource( env ))));
            }
            throw new AbortException(Messages.MercurialSCM_failed_to_clone(getSource( env )));
        }
        if(cloneExitCode!=0) {
            listener.error(Messages.MercurialSCM_failed_to_clone(getSource( env )));
            throw new AbortException(Messages.MercurialSCM_failed_to_clone(getSource( env )));
        }

        if (cachedSource != null && cachedSource.isUseCaches() && !cachedSource.isUseSharing()) {
            FilePath hgrc = repository.child(".hg/hgrc");
            if (hgrc.exists()) {
                String hgrcText = hgrc.readToString();
                if (!hgrcText.contains(cachedSource.getRepoLocation())) {
                    listener.error(".hg/hgrc did not contain " + cachedSource.getRepoLocation() + " as expected:\n" + hgrcText);
                    throw new AbortException(".hg/hgrc did not contain " + cachedSource.getRepoLocation() + " as expected:\n" + hgrcText);
                }
                hgrc.write(hgrcText.replace(cachedSource.getRepoLocation(), getSource( env )), null);
            }
            // Passing --rev disables hardlinks, so we need to recreate them:
            hg.run("--config", "extensions.relink=", "relink", cachedSource.getRepoLocation())
                    .pwd(repository).join(); // ignore failures
        }

        ArgumentListBuilder upArgs = new ArgumentListBuilder();
        upArgs.add("update");
        upArgs.add("--rev", toRevision);
        if (hg.run(upArgs).pwd(repository).join() != 0) {
            throw new AbortException("Failed to update " + getSource( env ) + " to rev " + toRevision);
        }

        String tip = hg.tip(repository, null);
        String rev = hg.tipNumber(repository, null);
        if (tip != null && rev != null) {
            build.addAction(new MercurialTagAction(tip, rev, getSubdir(env)));
        }
    }

    @Override
    public void buildEnvVars(AbstractBuild<?,?> build, Map<String, String> env) {
        MercurialTagAction a = findTag(build, env);
        if (a != null) {
            env.put("MERCURIAL_REVISION", a.id);
            env.put("MERCURIAL_REVISION_NUMBER", a.rev);
        }
    }

    private MercurialTagAction findTag(AbstractBuild<?, ?> build, Map<String, String> e) {
        for (Action action : build.getActions()) {
            if (action instanceof MercurialTagAction) {
                MercurialTagAction tag = (MercurialTagAction) action;
                // JENKINS-12162: differentiate plugins in different getSubdir()s
                EnvVars env = new EnvVars( e );
                String ourSubDir = getSubdir( env );
                String tagSubDir = tag.getSubdir( );
                if ((ourSubDir == null && tagSubDir == null) || (ourSubDir != null && ourSubDir.equals(tagSubDir))) {
                    return tag;
                }
            }
        }
        return null;
    }

    @Override
    public ChangeLogParser createChangeLogParser() {
        return new MercurialChangeLogParser(_modules);
    }

    @Override public FilePath getModuleRoot(FilePath workspace, AbstractBuild build) {
        if ( build != null )
        {
            try {
                EnvVars env = build.getEnvironment( );
                return workspace2Repo(workspace, env);
            } catch (IOException ex) {
                Logger.getLogger(MercurialSCM.class.getName()).log(Level.SEVERE, null, ex);
            } catch (InterruptedException ex) {
                Logger.getLogger(MercurialSCM.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        
        EnvVars env = new EnvVars( );
        return workspace2Repo(workspace, env);
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    public String getModules() {
        return modules;
    }

    private boolean causedByMissingHg(IOException e) {
        String message = e.getMessage();
        return message != null && message.startsWith("Cannot run program") && message.endsWith("No such file or directory");
    }

    static boolean CACHE_LOCAL_REPOS = false;
    private @CheckForNull PossiblyCachedRepo cachedSource(Node node, Launcher launcher, TaskListener listener, boolean fromPolling) {
        if (!CACHE_LOCAL_REPOS && source.matches("(file:|[/\\\\]).+")) {
            return null;
        }
        boolean useCaches = false;
        MercurialInstallation _installation = null;
        for (MercurialInstallation inst : MercurialInstallation.allInstallations()) {
            if (inst.getName().equals(installation)) {
                useCaches = inst.isUseCaches();
                _installation = inst;
                break;
            }
        }
        if (!useCaches) {
            return null;
        }
        try {
            FilePath cache = Cache.fromURL(source).repositoryCache(this, node, launcher, listener, fromPolling);
            if (cache != null) {
                return new PossiblyCachedRepo(cache.getRemote(), _installation.isUseCaches(), _installation.isUseSharing());
            } else {
                listener.error("Failed to use repository cache for " + source);
                return null;
            }
        } catch (Exception x) {
            x.printStackTrace(listener.error("Failed to use repository cache for " + source));
            return null;
        }
    }

    private static class PossiblyCachedRepo {
        private final String repoLocation;
        private final boolean useCaches;
        private final boolean useSharing;

        private PossiblyCachedRepo(String repoLocation, boolean useCaches, boolean useSharing) {
            this.repoLocation = repoLocation;
            this.useCaches = useCaches;
            this.useSharing = useSharing;
        }

        public String getRepoLocation() {
            return repoLocation;
        }

        public boolean isUseSharing() {
            return useSharing;
        }

        public boolean isUseCaches() {
            return useCaches;
        }
    }

    @Extension
    public static final class DescriptorImpl extends SCMDescriptor<MercurialSCM> {

        private String hgExe;

        public DescriptorImpl() {
            super(HgBrowser.class);
            load();
        }

        public String getDisplayName() {
            return "Mercurial";
        }

        /**
         * Path to mercurial executable.
         */
        public String getHgExe() {
            if (hgExe == null) {
                return "hg";
            }
            return hgExe;
        }

        @Override
        public SCM newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            return super.newInstance(req, formData);
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            hgExe = req.getParameter("mercurial.hgExe");
            save();
            return true;
        }

    }

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(MercurialSCM.class.getName());
}
