package org.jenkinsci.plugins.pitmutation.targets;

import java.io.Serializable;
import java.util.*;

import hudson.model.AbstractBuild;
import org.jenkinsci.plugins.pitmutation.Mutation;
import org.jenkinsci.plugins.pitmutation.MutationReport;
import org.jenkinsci.plugins.pitmutation.PitBuildAction;

/**
 * @author edward
 */
public class MutationResult implements Serializable {
  public MutationResult(PitBuildAction action) {
    action_ = action;
  }

  public AbstractBuild getOwner() {
    return action_.getOwner();
  }

  public MutationStats getMutationStats() {
    return new MutationStats();
  }

  public Collection<String> findNewTargets() {
    MutationReport report = action_.getReport();
    MutationReport previous = action_.getPreviousAction().getReport();

    Set<String> targets = new HashSet<String>(report.sourceFilenames());

    targets.removeAll(previous.sourceFilenames());

    return targets;
  }

  public Collection<Mutation> findDifferentMutations(String className) {
    MutationReport report = action_.getReport();
    MutationReport previous = action_.getPreviousAction().getReport();

    Set<Mutation> mutations = new HashSet<Mutation>(report.getMutationsForClassName(className));

    mutations.removeAll(previous.getMutationsForClassName(className));
    return mutations;
  }

  public Collection<Mutation> findNewSurvivors(String className) {
    Collection<Mutation> survivors = new ArrayList<Mutation>();

    for (Mutation m : findDifferentMutations(className)) {
      if (!m.isDetected()) {
        survivors.add(m);
      }
    }

    return survivors;
  }

  private PitBuildAction action_;
}
