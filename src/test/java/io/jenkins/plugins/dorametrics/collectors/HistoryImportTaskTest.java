package io.jenkins.plugins.dorametrics.collectors;

import hudson.model.FreeStyleProject;
import hudson.model.TaskListener;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import jenkins.model.Jenkins;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The task decides whether the first import runs and whether it is recorded as having run.
 * Getting that wrong either loses the import or repeats it hourly forever.
 */
public class HistoryImportTaskTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private MetricsStore store;

    @Before
    public void setUp() {
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
    }

    private HistoryImportTask task() {
        HistoryImportTask task = Jenkins.get().getExtensionList(HistoryImportTask.class).get(0);
        assertNotNull("HistoryImportTask should be registered", task);
        return task;
    }

    private int storedBuilds(String jobName) {
        long now = System.currentTimeMillis();
        return store.getBuilds(jobName, now - 86_400_000L, now + 86_400_000L).size();
    }

    @Test
    public void runsAndMarksDoneWhenTheFlagIsOff() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExcludedJobPattern("task-on");
        FreeStyleProject job = j.createFreeStyleProject("task-on");
        j.buildAndAssertSuccess(job);
        config.setExcludedJobPattern("");

        assertFalse("precondition: the import has not run", config.isHistoryImportDone());
        assertEquals("precondition: the listener skipped it", 0, storedBuilds("task-on"));

        task().execute(TaskListener.NULL);

        assertEquals("the task should have imported the build", 1, storedBuilds("task-on"));
        assertTrue("and recorded that it ran", DoraGlobalConfiguration.get().isHistoryImportDone());
    }

    @Test
    public void doesNothingWhenTheFlagIsAlreadySet() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExcludedJobPattern("task-off");
        FreeStyleProject job = j.createFreeStyleProject("task-off");
        j.buildAndAssertSuccess(job);
        config.setExcludedJobPattern("");

        config.markHistoryImportDone();

        task().execute(TaskListener.NULL);

        assertEquals("a second run must not import anything", 0, storedBuilds("task-off"));
    }

    /**
     * A run that wrote nothing at all is not the import having happened, so the flag stays
     * off and the next hour tries again. A partial failure does count, because walking the
     * whole instance every hour over one unreadable build costs more than it saves.
     */
    @Test
    public void marksDoneOnlyWhenSomethingWasWritten() {
        assertFalse("nothing written and failures: leave it to run again",
                HistoryImportTask.shouldMarkDone(new BuildHistoryImporter.Result(2, 0, 0, 5, 10, true)));

        assertTrue("some written, some failed: the run happened",
                HistoryImportTask.shouldMarkDone(new BuildHistoryImporter.Result(2, 3, 0, 1, 10, true)));

        assertTrue("nothing to do at all: the run still happened",
                HistoryImportTask.shouldMarkDone(new BuildHistoryImporter.Result(2, 0, 4, 0, 10, true)));
    }

    /**
     * The case that made this necessary: interrupting the thread before the task runs makes
     * the loop stop on its first check, so every counter is zero. Before completion was
     * tracked the flag was set anyway and the import never happened on that instance.
     */
    @Test
    public void anInterruptedRunLeavesTheFlagOff() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExcludedJobPattern("interrupted-job");
        j.buildAndAssertSuccess(j.createFreeStyleProject("interrupted-job"));
        config.setExcludedJobPattern("");

        assertFalse("precondition", config.isHistoryImportDone());

        Thread.currentThread().interrupt();
        try {
            task().execute(TaskListener.NULL);
        } finally {
            Thread.interrupted(); // clear it so the rest of the suite is unaffected
        }

        assertFalse("an interrupted run must not count as the import having happened",
                DoraGlobalConfiguration.get().isHistoryImportDone());
        assertEquals("and must not have imported anything", 0, storedBuilds("interrupted-job"));
    }

    /**
     * A run that stopped because Jenkins was going down reports zero of everything, which
     * reads exactly like a run that found nothing to import. Marking that done would mean
     * the import never happens on that instance, so completion is tracked separately from
     * the counters.
     */
    @Test
    public void neverMarksDoneWhenTheRunDidNotFinish() {
        assertFalse("stopped before it walked anything",
                HistoryImportTask.shouldMarkDone(new BuildHistoryImporter.Result(0, 0, 0, 0, 5, false)));

        assertFalse("stopped part way, even having recorded plenty",
                HistoryImportTask.shouldMarkDone(new BuildHistoryImporter.Result(3, 7, 2, 0, 5, false)));
    }
}
