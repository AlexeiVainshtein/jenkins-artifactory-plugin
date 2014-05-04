/*
 * Copyright (C) 2011 JFrog Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jfrog.hudson.release.scm.git;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;
import hudson.security.ACL;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jfrog.hudson.release.scm.AbstractScmManager;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

/**
 * Interacts with Git repository for the various release operations.
 *
 * @author Yossi Shaul
 */
public class GitManager extends AbstractScmManager<GitSCM> {
    private static Logger debuggingLogger = Logger.getLogger(GitManager.class.getName());

    public GitManager(AbstractBuild<?, ?> build, TaskListener buildListener) {
        super(build, buildListener);
    }

    public void checkoutBranch(final String branch, final boolean create) throws IOException, InterruptedException {
        GitClient client = getGitClient();

        debuggingLogger.fine(String.format("Checkout Branch '%s' with create=%s", branch, create));
        if (create) {
            client.checkout(null, branch);
        } else {
            client.checkout(branch);
        }
    }

    public void commitWorkingCopy(final String commitMessage) throws IOException, InterruptedException {
        GitClient client = getGitClient();

        debuggingLogger.fine("Adding all files in the current directory");
        client.add(".");

        debuggingLogger.fine(String.format("Committing working copy with message '%s'", commitMessage));
        client.commit(commitMessage);
    }

    public void createTag(final String tagName, final String commitMessage)
            throws IOException, InterruptedException {
        GitClient client = getGitClient();

        log(buildListener, String.format("Creating tag '%s' with message '%s'", tagName, commitMessage));
        client.tag(tagName, commitMessage);
    }

    public void push(final RemoteConfig remoteRepository, final String branch) throws IOException, InterruptedException {
        GitClient client = getGitClient();

        log(buildListener, String.format("Pushing branch '%s' to '%s'", branch, getFirstGitURI(remoteRepository)));
        client.push(remoteRepository.getName(), "refs/heads/" + branch);
    }

    public void pushTag(final RemoteConfig remoteRepository, final String tagName) throws IOException, InterruptedException {
        GitClient client = getGitClient();

        String escapedTagName = tagName.replace(' ', '_');
        log(buildListener, String.format("Pushing tag '%s' to '%s'", escapedTagName, getFirstGitURI(remoteRepository)));
        client.push(remoteRepository.getName(), "refs/tags/" + escapedTagName);
    }

    public void revertWorkingCopy() throws IOException, InterruptedException {
        GitClient client = getGitClient();

        log(buildListener, "Reverting git working copy (reset --hard)");
        client.clean();
    }

    public void deleteLocalBranch(final String branch) throws IOException, InterruptedException {
        GitClient client = getGitClient();

        log(buildListener, "Deleting local git branch: " + branch);
        client.deleteBranch(branch);
    }

    public void deleteRemoteBranch(final RemoteConfig remoteRepository, final String branch)
    throws IOException, InterruptedException {
        GitClient client = getGitClient();

        log(buildListener, String.format("Deleting remote branch '%s' on '%s'", branch, getFirstGitURI(remoteRepository)));
        client.push(remoteRepository.getName(), ":refs/heads/" + branch);
    }

    public void deleteLocalTag(final String tag) throws IOException, InterruptedException {
        GitClient client = getGitClient();

        log(buildListener, "Deleting local tag: " + tag);
        client.deleteTag(tag);
    }

    public void deleteRemoteTag(final RemoteConfig remoteRepository, final String tag)
    throws IOException, InterruptedException {
        GitClient client = getGitClient();

        log(buildListener, String.format("Deleting remote tag '%s' from '%s'", tag, getFirstGitURI(remoteRepository)));
        client.push(remoteRepository.getName(), ":refs/tags/" + tag);
    }

    // This method is currently in use only by the SvnCoordinator
    public String getRemoteUrl(String defaultRemoteUrl) {
        if (StringUtils.isBlank(defaultRemoteUrl)) {
            RemoteConfig remoteConfig = getJenkinsScm().getRepositories().get(0);
            URIish uri = remoteConfig.getURIs().get(0);
            return uri.toPrivateString();
        }

        return defaultRemoteUrl;
    }

    public RemoteConfig getRemoteConfig(String defaultRemoteName) throws IOException {
        List<RemoteConfig> repositories = getJenkinsScm().getRepositories();
        if (StringUtils.isBlank(defaultRemoteName)) {
            if (repositories == null || repositories.isEmpty()) {
                throw new GitException("Git remote config repositories are null or empty.");
            }
            return repositories.get(0);
        }

        for (RemoteConfig remoteConfig : repositories) {
            if (remoteConfig.getName().equals(defaultRemoteName)) {
                return remoteConfig;
            }
        }

        throw new IOException("Target Remote Name doesn`t exist");
    }

    private GitClient getGitClient() throws IOException, InterruptedException {
        FilePath directory = getWorkingDirectory(getJenkinsScm(), build.getWorkspace());
        EnvVars env = build.getEnvironment(buildListener);

        Git git = new Git(buildListener, env);
        git.in(directory);

        /*
        * When init the git exe, the user dons`t have to add SSH credentials in the git plugin.
        *  This solution automatically takes the user default SSH ($HOME/.ssh)
        * */
        git.using(getJenkinsScm().getGitExe(build.getBuiltOn(), buildListener)); // git.exe
        GitClient client = git.getClient();

        client.setCommitter(StringUtils.defaultIfEmpty(env.get("GIT_COMMITTER_NAME"), ""),
                StringUtils.defaultIfEmpty(env.get("GIT_COMMITTER_EMAIL"), ""));
        client.setAuthor(StringUtils.defaultIfEmpty(env.get("GIT_AUTHOR_NAME"), ""),
                StringUtils.defaultIfEmpty(env.get("GIT_AUTHOR_EMAIL"), ""));

        addRemoteRepoToConfig(client);

        addCredentialsToGitClient(client);

        return client;
    }

    private String getFirstGitURI(RemoteConfig remoteRepository) {
        List<URIish> urIs = remoteRepository.getURIs();
        if (urIs == null || urIs.isEmpty()) {
            throw new GitException("Error performing push tag command, repository URIs are null or empty.");
        }

        return urIs.get(0).toString();
    }

    /*
    * In cause the remote repository is not exists in the git config file
    * */
    private void addRemoteRepoToConfig(GitClient client) throws InterruptedException {
        GitSCM gitScm = getJenkinsScm();
        for (UserRemoteConfig uc : gitScm.getUserRemoteConfigs()) {
            if (client.getRemoteUrl(uc.getName()) == null)
                client.setRemoteUrl(uc.getName(), uc.getUrl());
        }
    }

    private void addCredentialsToGitClient(GitClient client) {
        GitSCM gitScm = getJenkinsScm();
        for (UserRemoteConfig uc : gitScm.getUserRemoteConfigs()) {
            if (uc.getCredentialsId() != null) {
                String url = uc.getUrl();
                StandardUsernameCredentials credentials = CredentialsMatchers
                        .firstOrNull(
                                CredentialsProvider.lookupCredentials(StandardUsernameCredentials.class,
                                        build.getProject(), ACL.SYSTEM, URIRequirementBuilder.fromUri(url).build()),
                                CredentialsMatchers.allOf(CredentialsMatchers.withId(uc.getCredentialsId()),
                                        GitClient.CREDENTIALS_MATCHER));
                if (credentials != null) {
                    client.addCredentials(url, credentials);
                }
            }
        }
    }

    private FilePath getWorkingDirectory(GitSCM gitSCM, FilePath ws) throws IOException {
        // working directory might be relative to the workspace
        String relativeTargetDir = gitSCM.getRelativeTargetDir() == null ? "" : gitSCM.getRelativeTargetDir();
        return new FilePath(ws, relativeTargetDir);
    }
}