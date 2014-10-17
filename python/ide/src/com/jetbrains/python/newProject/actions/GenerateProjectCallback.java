/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.python.newProject.actions;

import com.intellij.ide.RecentProjectsManager;
import com.intellij.ide.util.projectWizard.WebProjectTemplate;
import com.intellij.internal.statistic.UsageTrigger;
import com.intellij.internal.statistic.beans.ConvertUsagesUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkAdditionalData;
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil;
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.DirectoryProjectGenerator;
import com.intellij.platform.PlatformProjectOpenProcessor;
import com.intellij.projectImport.ProjectOpenedCallback;
import com.intellij.util.NullableConsumer;
import com.jetbrains.python.configuration.PyConfigurableInterpreterList;
import com.jetbrains.python.newProject.PyNewProjectSettings;
import com.jetbrains.python.newProject.PythonProjectGenerator;
import com.jetbrains.python.sdk.PyDetectedSdk;
import com.jetbrains.python.sdk.PySdkService;
import com.jetbrains.python.sdk.PythonSdkAdditionalData;
import com.jetbrains.python.sdk.PythonSdkType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;

public class GenerateProjectCallback implements NullableConsumer<AbstractProjectSettingsStep> {
  private static final Logger LOG = Logger.getInstance(GenerateProjectCallback.class);
  @Nullable private final Runnable myRunnable;

  public GenerateProjectCallback(@Nullable final Runnable runnable) {

    myRunnable = runnable;
  }

  @Override
  public void consume(@Nullable AbstractProjectSettingsStep settingsStep) {
    if (myRunnable != null) {
      myRunnable.run();
    }
    if (settingsStep == null) return;

    Sdk sdk = settingsStep.getSdk();
    final Project project = ProjectManager.getInstance().getDefaultProject();
    final ProjectSdksModel model = PyConfigurableInterpreterList.getInstance(project).getModel();
    if (sdk instanceof PyDetectedSdk) {
      final String name = sdk.getName();
      VirtualFile sdkHome = ApplicationManager.getApplication().runWriteAction(new Computable<VirtualFile>() {
        @Override
        public VirtualFile compute() {
          return LocalFileSystem.getInstance().refreshAndFindFileByPath(name);
        }
      });
      PySdkService.getInstance().solidifySdk(sdk);
      sdk = SdkConfigurationUtil.setupSdk(ProjectJdkTable.getInstance().getAllJdks(), sdkHome, PythonSdkType.getInstance(), true, null,
                                          null);
      model.addSdk(sdk);
      settingsStep.setSdk(sdk);
      try {
        model.apply();
      }
      catch (ConfigurationException exception) {
        LOG.error("Error adding detected python interpreter " + exception.getMessage());
      }
    }
    Project newProject = generateProject(project, settingsStep);
    if (newProject != null) {
      SdkConfigurationUtil.setDirectoryProjectSdk(newProject, sdk);
      final List<Sdk> sdks = PythonSdkType.getAllSdks();
      for (Sdk s : sdks) {
        final SdkAdditionalData additionalData = s.getSdkAdditionalData();
        if (additionalData instanceof PythonSdkAdditionalData) {
          ((PythonSdkAdditionalData)additionalData).reassociateWithCreatedProject(newProject);
        }
      }
    }
  }

  @Nullable
  private static Project generateProject(@NotNull final Project project, @NotNull final AbstractProjectSettingsStep settings) {
    final DirectoryProjectGenerator generator = settings.getProjectGenerator();
    final File location = new File(settings.getProjectLocation());
    if (!location.exists() && !location.mkdirs()) {
      Messages.showErrorDialog(project, "Cannot create directory '" + location + "'", "Create Project");
      return null;
    }

    final VirtualFile baseDir = ApplicationManager.getApplication().runWriteAction(new Computable<VirtualFile>() {
      public VirtualFile compute() {
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(location);
      }
    });
    LOG.assertTrue(baseDir != null, "Couldn't find '" + location + "' in VFS");
    baseDir.refresh(false, true);

    if (baseDir.getChildren().length > 0) {
      int rc = Messages.showYesNoDialog(project,
                                        "The directory '" + location +
                                        "' is not empty. Would you like to create a project from existing sources instead?",
                                        "Create New Project", Messages.getQuestionIcon());
      if (rc == Messages.YES) {
        return PlatformProjectOpenProcessor.getInstance().doOpenProject(baseDir, null, false);
      }
    }

    String generatorName = generator == null ? "empty" : ConvertUsagesUtil.ensureProperKey(generator.getName());
    UsageTrigger.trigger("NewDirectoryProjectAction." + generatorName);

    RecentProjectsManager.getInstance().setLastProjectCreationLocation(location.getParent());

    return PlatformProjectOpenProcessor.doOpenProject(baseDir, null, false, -1, new ProjectOpenedCallback() {
      @Override
      public void projectOpened(Project project, Module module) {
        if (generator != null) {
          Object projectSettings = null;
          if (generator instanceof PythonProjectGenerator) {
            projectSettings = ((PythonProjectGenerator)generator).getProjectSettings();
          }
          else if (generator instanceof WebProjectTemplate) {
            projectSettings = ((WebProjectTemplate)generator).getPeer().getSettings();
          }
          if (projectSettings instanceof PyNewProjectSettings) {
            ((PyNewProjectSettings)projectSettings).setSdk(settings.getSdk());
            ((PyNewProjectSettings)projectSettings).setInstallFramework(settings.installFramework());
          }
          //noinspection unchecked
          generator.generateProject(project, baseDir, projectSettings, module);
        }
      }
    }, false);
  }
}
