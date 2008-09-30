package git4idea.actions;
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
 * Copyright 2008 MQSoftware
 * Authors: Mark Scott
 */

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.commands.GitCommand;
import git4idea.commands.GitCommandRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Git un-stash action
 */
public class Unstash extends BasicAction {
    protected void perform(@NotNull Project project, GitVcs vcs, @NotNull List<VcsException> exceptions, @NotNull VirtualFile[] affectedFiles) throws VcsException {
        saveAll();

        if (!ProjectLevelVcsManager.getInstance(project).checkAllFilesAreUnder(GitVcs.getInstance(project), affectedFiles))
            return;

        final Set<VirtualFile> roots = GitUtil.getVcsRootsForFiles(project, affectedFiles);

        for (VirtualFile root : roots) {
            GitCommand command = new GitCommand(project, vcs.getSettings(), root);
            String[] stashList = command.stashList();
            if (stashList == null || stashList.length == 0) continue;
            int stashIndex = Messages.showChooseDialog("Select stash to restore: ",
                    "UnStash Changes for " + root.getPath(), stashList, stashList[0], Messages.getQuestionIcon());
            if (stashIndex < 0)
                continue;

            GitCommandRunnable cmdr = new GitCommandRunnable(project, vcs.getSettings(), root);
            cmdr.setCommand(GitCommand.STASH_CMD);
            String stashName = stashList[stashIndex].split(":")[0];
            cmdr.setArgs(new String[]{"apply", stashName});

            ProgressManager manager = ProgressManager.getInstance();
            //TODO: make this async so the git command output can be seen in the version control window as it happens...
            manager.runProcessWithProgressSynchronously(cmdr, "UnStashing changes... ", false, project);

            VcsException ex = cmdr.getException();
            if (ex != null) {
                Messages.showErrorDialog(project, ex.getMessage(), "Error occurred during 'git stash apply'");
                break;
            }
        }
    }

    @NotNull
    protected String getActionName(@NotNull AbstractVcs abstractvcs) {
        return "UnStash";
    }

    protected boolean isEnabled(@NotNull Project project, @NotNull GitVcs vcs, @NotNull VirtualFile... vFiles) {
        if (!ProjectLevelVcsManager.getInstance(project).checkAllFilesAreUnder(GitVcs.getInstance(project), vFiles))
            return false;

        final Map<VirtualFile, List<VirtualFile>> roots = GitUtil.sortFilesByVcsRoot(project, vFiles);

        try {
            for (VirtualFile root : roots.keySet()) {
                GitCommand command = new GitCommand(project, vcs.getSettings(), root);
                String[] slist = command.stashList();
                if (slist != null && slist.length > 0) return true;
            }
        } catch (VcsException e) {
            return false;
        }

        return false;
    }
}