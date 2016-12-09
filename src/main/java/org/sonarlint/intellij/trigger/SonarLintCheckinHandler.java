/*
 * SonarLint for IntelliJ IDEA
 * Copyright (C) 2015 SonarSource
 * sonarlint@sonarsource.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonarlint.intellij.trigger;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.CheckinProjectPanel;
import com.intellij.openapi.vcs.changes.CommitExecutor;
import com.intellij.openapi.vcs.checkin.CheckinHandler;
import com.intellij.openapi.vcs.ui.RefreshableOnComponent;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.NonFocusableCheckBox;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.util.PairConsumer;
import com.intellij.util.ui.UIUtil;
import java.awt.BorderLayout;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jetbrains.annotations.Nullable;
import org.sonarlint.intellij.config.global.SonarLintGlobalSettings;
import org.sonarlint.intellij.issue.ChangedFilesIssues;
import org.sonarlint.intellij.issue.IssueManager;
import org.sonarlint.intellij.issue.LiveIssue;
import org.sonarlint.intellij.ui.SonarLintToolWindowFactory;
import org.sonarlint.intellij.util.SonarLintUtils;

public class SonarLintCheckinHandler extends CheckinHandler {
  private static final Logger LOGGER = Logger.getInstance(SonarLintCheckinHandler.class);
  private static final String ACTIVATED_OPTION_NAME = "SONARLINT_PRECOMMIT_ANALYSIS";
  private static final String SONARLINT_TOOL_WINDOW_ID = "SonarLint";

  private final ToolWindowManager toolWindowManager;
  private final SonarLintGlobalSettings globalSettings;
  private final Project project;
  private final CheckinProjectPanel checkinPanel;
  private JCheckBox checkBox;

  public SonarLintCheckinHandler(ToolWindowManager toolWindowManager, SonarLintGlobalSettings globalSettings,
    Project project, CheckinProjectPanel checkinPanel) {
    this.toolWindowManager = toolWindowManager;
    this.globalSettings = globalSettings;
    this.project = project;
    this.checkinPanel = checkinPanel;
  }

  @Override
  @Nullable
  public RefreshableOnComponent getBeforeCheckinConfigurationPanel() {
    this.checkBox = new NonFocusableCheckBox("Perform SonarLint analysis");
    return new MyRefreshableOnComponent(checkBox);
  }

  @Override
  public ReturnResult beforeCheckin(@Nullable CommitExecutor executor, PairConsumer<Object, Object> additionalDataConsumer) {
    if (checkBox != null && !checkBox.isSelected()) {
      return ReturnResult.COMMIT;
    }

    Collection<VirtualFile> affectedFiles = checkinPanel.getVirtualFiles();
    SonarLintSubmitter submitter = SonarLintUtils.get(project, SonarLintSubmitter.class);
    // this will block EDT (modal)
    try {
      submitter.submitFilesModal(affectedFiles, TriggerType.CHECK_IN);
      return processResult(affectedFiles);
    } catch (Exception e) {
      String msg = "SonarLint - Error analysing " + affectedFiles.size() + " changed file(s).";
      if (e.getMessage() != null) {
        msg = msg + ": " + e.getMessage();
      }
      LOGGER.error("msg", e);
      Messages.showErrorDialog(project, msg, "Error Analysing Files");
      return ReturnResult.CANCEL;
    }
  }

  private ReturnResult processResult(Collection<VirtualFile> affectedFiles) {
    ChangedFilesIssues changedFilesIssues = SonarLintUtils.get(project, ChangedFilesIssues.class);
    IssueManager issueManager = SonarLintUtils.get(project, IssueManager.class);

    Map<VirtualFile, Collection<LiveIssue>> map = affectedFiles.stream()
      .collect(Collectors.toMap(Function.identity(), issueManager::getForFile));

    long numIssues = map.entrySet().stream()
      .flatMap(e -> e.getValue().stream())
      .filter(i -> !i.isResolved())
      .count();
    changedFilesIssues.set(map);

    long numBlockerIssues = map.entrySet().stream()
      .flatMap(e -> e.getValue().stream())
      .filter(i -> !i.isResolved())
      .filter(i -> "BLOCKER".equals(i.getSeverity()))
      .count();

    if (numIssues == 0) {
      return ReturnResult.COMMIT;
    }

    long numFiles = map.keySet().size();

    String msg = createMessage(numFiles, numIssues, numBlockerIssues);
    if (ApplicationManager.getApplication().isHeadlessEnvironment()) {
      LOGGER.info(msg);
      return ReturnResult.CANCEL;
    }

    return showYesNoCancel(msg);
  }

  private static String createMessage(long filesAnalysed, long numIssues, long numBlockerIssues) {
    String files = filesAnalysed == 1 ? "file" : "files";
    String issues = numIssues == 1 ? "issue" : "issues";

    if (numBlockerIssues > 0) {
      String blocker = numBlockerIssues == 1 ? "issue" : "issues";
      return String.format("SonarLint analysis on %d %s found %d %s (including %d blocker %s)", filesAnalysed, files,
        numIssues, issues, numBlockerIssues, blocker);
    } else {
      return String.format("SonarLint analysis on %d %s found %d %s", filesAnalysed, files, numIssues, issues);
    }
  }

  private ReturnResult showYesNoCancel(String resultStr) {
    final int answer = Messages.showYesNoCancelDialog(project,
      resultStr,
      "SonarLint Analysis Results",
      "Review Issues",
      "Commit Anyway",
      "Close",
      UIUtil.getWarningIcon());

    if (answer == Messages.YES) {
      showChangedFilesTab();
      return ReturnResult.CLOSE_WINDOW;
    } else if (answer == Messages.CANCEL) {
      return ReturnResult.CANCEL;
    } else {
      return ReturnResult.COMMIT;
    }
  }

  private void showChangedFilesTab() {
    ToolWindow toolWindow = toolWindowManager.getToolWindow(SONARLINT_TOOL_WINDOW_ID);
    if (toolWindow != null) {
      toolWindow.show(new ChangedFilesTabOpener(toolWindow));
    }
  }

  private static class ChangedFilesTabOpener implements Runnable {
    private final ToolWindow toolWindow;

    private ChangedFilesTabOpener(ToolWindow toolWindow) {
      this.toolWindow = toolWindow;
    }

    @Override public void run() {
      ContentManager contentManager = toolWindow.getContentManager();
      Content content = contentManager.findContent(SonarLintToolWindowFactory.TAB_CHANGED_FILES);
      if (content != null) {
        contentManager.setSelectedContent(content);
      }
    }
  }

  private class MyRefreshableOnComponent implements RefreshableOnComponent {
    private final JCheckBox checkBox;

    private MyRefreshableOnComponent(JCheckBox checkBox) {
      this.checkBox = checkBox;
    }

    @Override
    public JComponent getComponent() {
      JPanel panel = new JPanel(new BorderLayout());
      panel.add(checkBox);
      boolean dumb = DumbService.isDumb(project);
      checkBox.setEnabled(!dumb);
      checkBox.setToolTipText(dumb ? "SonarLint analysis is impossible until indices are up-to-date" : "");
      return panel;
    }

    @Override
    public void refresh() {
      // nothing to do
    }

    @Override
    public void saveState() {
      PropertiesComponent.getInstance(project).setValue(ACTIVATED_OPTION_NAME, Boolean.toString(checkBox.isSelected()));
    }

    @Override
    public void restoreState() {
      PropertiesComponent props = PropertiesComponent.getInstance(project);
      checkBox.setSelected(props.getBoolean(ACTIVATED_OPTION_NAME, globalSettings.isAutoTrigger()));
    }
  }
}