package org.jenkinsci.plugins.pitmutation;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.*;
import hudson.remoting.VirtualChannel;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.pitmutation.targets.MutationStats;
import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The type Pit publisher.
 *
 * @author edward
 */
public class PitPublisher extends Recorder implements SimpleBuildStep{

    /**
     * The constant DESCRIPTOR.
     */
    @Extension
  public static final BuildStepDescriptor<Publisher> DESCRIPTOR = new DescriptorImpl();

    /**
     * Instantiates a new Pit publisher.
     *
     * @param mutationStatsFile    the mutation stats file
     * @param minimumKillRatio     the minimum kill ratio
     * @param killRatioMustImprove the kill ratio must improve
     */
    @DataBoundConstructor
  public PitPublisher(String mutationStatsFile, float minimumKillRatio, boolean killRatioMustImprove) {
    mutationStatsFile_ = mutationStatsFile;
    killRatioMustImprove_ = killRatioMustImprove;
    minimumKillRatio_ = minimumKillRatio;
    buildConditions_ = new ArrayList<Condition>();
    buildConditions_.add(percentageThreshold(minimumKillRatio));
    if (killRatioMustImprove) {
      buildConditions_.add(mustImprove());
    }
  }

  @Override
  public void perform(@Nonnull Run<?, ?> build, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull TaskListener listener) throws InterruptedException, IOException {

    listener_ = listener;
    build_ = build;

      listener_.getLogger().println("Looking for PIT reports in " + workspace.getRemote());

      final FilePath moduleRoot =  workspace;

      ParseReportCallable fileCallable = new ParseReportCallable(mutationStatsFile_);
      FilePath[] reports = moduleRoot.act(fileCallable);
      publishReports(reports, new FilePath(build.getRootDir()));

      PitBuildAction action = new PitBuildAction(build);
      build.getActions().add(action);
      build.setResult(decideBuildResult(action));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Action getProjectAction(AbstractProject<?, ?> project) {
    return new PitProjectAction(project);
  }

    /**
     * Publish reports.
     *
     * @param reports     the reports
     * @param buildTarget the build target
     */
    void publishReports(FilePath[] reports, FilePath buildTarget) {
    for (int i = 0; i < reports.length; i++) {
      FilePath report = reports[i];
      listener_.getLogger().println("Publishing mutation report: " + report.getRemote());

      final FilePath targetPath = new FilePath(buildTarget, "mutation-report" + (i == 0 ? "" : i));
      try {
        reports[i].getParent().copyRecursiveTo(targetPath);
      } catch (IOException e) {
        Util.displayIOException(e, listener_);
        e.printStackTrace(listener_.fatalError("Unable to copy coverage from " + reports[i] + " to " + buildTarget));
        build_.setResult(Result.FAILURE);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }

    /**
     * Mutations report exists boolean.
     *
     * @param reportDir the report dir
     * @return the boolean
     */
    boolean mutationsReportExists(FilePath reportDir) {
    if (reportDir == null) {
      return false;
    }
    try {
      FilePath[] search = reportDir.list("**/mutations.xml");
      return search.length > 0;
    }
    catch (IOException e) {
      return false;
    }
    catch (InterruptedException e) {
      return false;
    }
  }

    /**
     * Decide build result result.
     *
     * @param action the action
     * @return the worst result from all conditions
     */
    public Result decideBuildResult(PitBuildAction action) {
    Result result = Result.SUCCESS;
    for (Condition condition : buildConditions_) {
      Result conditionResult = condition.decideResult(action);
      result = conditionResult.isWorseThan(result) ? conditionResult : result;
    }
    return result;
  }


    /**
     * Required by plugin config
     *
     * @return the minimum kill ratio
     */
    public float getMinimumKillRatio() {
    return minimumKillRatio_;
  }

    /**
     * Required by plugin config
     *
     * @return the kill ratio must improve
     */
    public boolean getKillRatioMustImprove() {
    return killRatioMustImprove_;
  }

    /**
     * Required by plugin config
     *
     * @return the mutation stats file
     */
    public String getMutationStatsFile() {
    return mutationStatsFile_;
  }

  private Condition percentageThreshold(final float percentage) {
    return new Condition() {
      public Result decideResult(PitBuildAction action) {
        MutationStats stats = action.getReport().getMutationStats();
        listener_.getLogger().println("Kill ratio is " + stats.getKillPercent() +"% ("
                                      + stats.getKillCount() + "  " + stats.getTotalMutations() +")");
        return stats.getKillPercent() >= percentage ? Result.SUCCESS : Result.FAILURE;
      }
    };
  }

  private Condition mustImprove() {
    return new Condition() {
      public Result decideResult(final PitBuildAction action) {
        PitBuildAction previousAction = action.getPreviousAction();
        if (previousAction != null) {
          MutationStats stats = previousAction.getReport().getMutationStats();
          listener_.getLogger().println("Previous kill ratio was " + stats.getKillPercent() + "%");
          return action.getReport().getMutationStats().getKillPercent() <= stats.getKillPercent()
                  ? Result.SUCCESS : Result.UNSTABLE ;
        }
        else {
          return Result.SUCCESS;
        }
      }
    };
  }

  @Override
  public BuildStepDescriptor<Publisher> getDescriptor() {
    return DESCRIPTOR;
  }

  public BuildStepMonitor getRequiredMonitorService() {
    return BuildStepMonitor.BUILD;
  }

  private FilePath getReportDir(FilePath root) throws IOException, InterruptedException {
    FilePath reportsDir = new FilePath(root, mutationStatsFile_);
    if (reportsDir.isDirectory()) {
      return reportsDir;
    }
    else {
      return reportsDir.getParent();
    }
  }

  private List<Condition> buildConditions_;
  private String mutationStatsFile_;
  private boolean killRatioMustImprove_;
  private float minimumKillRatio_;
  private transient TaskListener listener_;
  private Run<?,?> build_;


    /**
     * The type Descriptor.
     */
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        /**
         * Instantiates a new Descriptor.
         */
        public DescriptorImpl() {
      super(PitPublisher.class);
    }

    @Override
    public String getDisplayName() {
      return Messages.PitPublisher_DisplayName();
    }

    @Override
    public boolean isApplicable(Class<? extends AbstractProject> aClass) {
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
      req.bindParameters(this, "pitmutation");
      save();
      return super.configure(req, formData);
    }

    /**
     * Creates a new instance of {@link PitPublisher} from a submitted form.
     */
    @Override
    public PitPublisher newInstance(StaplerRequest req, JSONObject formData) throws FormException {
      PitPublisher instance = req.bindJSON(PitPublisher.class, formData);
      return instance;
    }
  }

    /**
     * The type Parse report callable.
     */
    public static class ParseReportCallable implements FilePath.FileCallable<FilePath[]> {

    private static final long serialVersionUID = 1L;

    private final String reportFilePath;

        /**
         * Instantiates a new Parse report callable.
         *
         * @param reportFilePath the report file path
         */
        public ParseReportCallable(String reportFilePath) {
      this.reportFilePath = reportFilePath;
    }

    public FilePath[] invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
      FilePath[] r = new FilePath(f).list(reportFilePath);
      if (r.length < 1) {
        throw new IOException("No reports found at location:" + reportFilePath);
      }
      return r;
    }

    @Override
    public void checkRoles(RoleChecker roleChecker) throws SecurityException {

    }
  }
}
