package git4idea.envs;
/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the License.
 *
 * Copyright 2007 Decentrix Inc
 * Copyright 2007 Aspiro AS
 * Copyright 2008 MQSoftware
 * Authors: gevession, Erlend Simonsen & Mark Scott
 *
 * This code was originally derived from the MKS & Mercurial IDEA VCS plugins
 */

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.CheckinProjectPanel;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.checkin.CheckinEnvironment;
import com.intellij.openapi.vcs.ui.RefreshableOnComponent;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.GitUtil;
import git4idea.actions.Add;
import git4idea.actions.Delete;
import git4idea.commands.GitCommand;
import git4idea.config.GitVcsSettings;
import git4idea.vfs.GitVirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Git environment for commit operations.
 */
public class GitCheckinEnvironment implements CheckinEnvironment {
    private Project project;
    private GitVcsSettings settings;
    private String tmpDir = System.getProperty("java.io.tmpdir");
    private File commitMsgFile = new File(tmpDir + File.separator + ".git-commit-msg");
    private static String DEF_COMMIT_MSG =
            "\n# Brief commit desciption here\n\n# Full commit description here (comment lines starting with '#' will not be included)\n\n";

    public GitCheckinEnvironment(@NotNull Project project, @NotNull GitVcsSettings settings) {
        this.project = project;
        this.settings = settings;
    }

    public void setProject(@NotNull Project project) {
        this.project = project;
    }

    public void setSettings(@NotNull GitVcsSettings settings) {
        this.settings = settings;
    }

    @Override
    @Nullable
    public RefreshableOnComponent createAdditionalOptionsPanel(CheckinProjectPanel panel) {
        return null;
    }

    @Override
    @Nullable
    public String getDefaultMessageFor(FilePath[] filesToCheckin) {
        if(filesToCheckin == null || filesToCheckin.length == 0) return null;
        GitCommand command = new GitCommand(project, settings, GitUtil.getVcsRoot(project,filesToCheckin[0]));
        String commitTemplate = command.getCommitTemplate();

        if (commitTemplate != null)
            return commitTemplate;

        if (commitMsgFile.exists() && commitMsgFile.canRead()) {
            byte[] msgBytes = new byte[(int) commitMsgFile.length()];
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(commitMsgFile);
                fis.read(msgBytes);
                fis.close();
                return new String(msgBytes);
            } catch (Exception e) {
                return DEF_COMMIT_MSG;
            } finally {
                try {
                    if (fis != null)
                        fis.close();
                } catch (IOException ie) {
                    commitMsgFile.delete();
                }
            }
        } else {
            return DEF_COMMIT_MSG;
        }
    }

    @Override
    public String prepareCheckinMessage(String text) {
        return text;
    }

    @Nullable
    public String getHelpId() {
        return null;
    }

    @Override
    public String getCheckinOperationName() {
        return "Commit";
    }

    @Override
    public boolean showCheckinDialogInAnyCase() {
        return true;
    }

    @SuppressWarnings({"ConstantConditions"})
    @Override
    public List<VcsException> commit(@NotNull final List<Change> changes, @NotNull final String message) {
        final int changeCount = changes.size();
        final List<VcsException> exceptions = new ArrayList<VcsException>(changeCount);
        if (changeCount == 0) return null;

        if(message != null && message.length() > 0) {
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(commitMsgFile);
                fos.write(message.getBytes());
            } catch (IOException ioe) {
            } finally {
                try {
                    if (fos != null)
                        fos.close();
                } catch (IOException ioe) {
                }
            }
        }

        Runnable command = new Runnable() {
            public void run() {
                final ProgressIndicator progress = ProgressManager.getInstance().getProgressIndicator();
                progress.setIndeterminate(true);

                Map<VirtualFile, List<Change>> sortedChanges = sortChangesByVcsRoot(changes);

                if (changeCount == 1)
                    progress.setText2("Commiting change...");
                else
                    progress.setText2("Commiting " + changes.size() + " changes...");

                for (VirtualFile root : sortedChanges.keySet()) {
                    GitCommand command = new GitCommand(project, settings, root);
                    Set<VirtualFile> files = new HashSet<VirtualFile>();
                    for (Change change : changes) {
                        if (change.getFileStatus().equals(FileStatus.MODIFIED))
                            files.add(new GitVirtualFile(project, change.getAfterRevision().getFile().getPath(),
                                    GitVirtualFile.Status.MODIFIED));
                        else if (change.getFileStatus().equals(FileStatus.ADDED))
                            files.add(new GitVirtualFile(project, change.getAfterRevision().getFile().getPath(),
                                    GitVirtualFile.Status.ADDED));
                        else if (change.getFileStatus().equals(FileStatus.DELETED))
                            files.add(new GitVirtualFile(project, change.getBeforeRevision().getFile().getPath(),
                                    GitVirtualFile.Status.DELETED));
                    }
                    try {
                        command.commit(files.toArray(new VirtualFile[files.size()]), message);
                    }
                    catch (VcsException e) {
                        exceptions.add(e);
                    }
                }
            }
        };

        if (ApplicationManager.getApplication().isDispatchThread()) {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(command, "Commit", false, project);
        } else {
            command.run();
        }

        return exceptions;
    }

    @Override
    public List<VcsException> scheduleMissingFileForDeletion(List<FilePath> files) {
        try {
            VirtualFile[] vfiles = new VirtualFile[files.size()];
            int count = 0;
            for (FilePath file : files) {
                vfiles[count++] = file.getVirtualFile();
            }
            Delete.deleteFiles(project, vfiles);
            return Collections.emptyList();
        } catch (VcsException e) {
            return Collections.singletonList(e);
        }
    }

    @Override
    public List<VcsException> scheduleUnversionedFilesForAddition(List<VirtualFile> files) {
        try {
            Add.addFiles(project, files.toArray(new VirtualFile[files.size()]));
            return Collections.emptyList();
        } catch (VcsException e) {
            return Collections.singletonList(e);
        }
    }

    private Map<VirtualFile, List<Change>> sortChangesByVcsRoot(@NotNull List<Change> changes) {
        Map<VirtualFile, List<Change>> result = new HashMap<VirtualFile, List<Change>>();

        for (Change change : changes) {
            final ContentRevision afterRevision = change.getAfterRevision();
            final ContentRevision beforeRevision = change.getBeforeRevision();

            final FilePath filePath = afterRevision != null ? afterRevision.getFile() : beforeRevision.getFile();
            final VirtualFile vcsRoot = GitUtil.getVcsRoot(project, filePath);

            List<Change> changeList = result.get(vcsRoot);
            if (changeList == null) {
                changeList = new ArrayList<Change>();
                result.put(vcsRoot, changeList);
            }
            changeList.add(change);
        }

        return result;
    }
}