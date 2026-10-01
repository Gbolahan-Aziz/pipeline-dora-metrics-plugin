package io.jenkins.plugins.dorametrics.collectors;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.FakeChangeLogSCM;
import org.jvnet.hudson.test.JenkinsRule;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * A change log entry has no commit id unless its SCM gives it one, and commit_sha is NOT NULL.
 * Now that a build and its children go in as one transaction, such an entry would fail the
 * insert and roll the build back with it, so the build would never be stored at all.
 */
public class CommitsWithoutIdTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Before
    public void setUp() {
        MetricsStore.setInstance(null);
    }

    private FreeStyleBuild buildWithTwoIdlessChanges(String jobName) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(jobName);
        FakeChangeLogSCM scm = new FakeChangeLogSCM();
        scm.addChange().withAuthor("dev").withMsg("first");
        scm.addChange().withAuthor("dev").withMsg("second");
        job.setScm(scm);
        return j.buildAndAssertSuccess(job);
    }

    @Test
    public void theBuildIsStoredWhenNoChangeHasACommitId() throws Exception {
        FreeStyleBuild run = buildWithTwoIdlessChanges("no-commit-ids");

        int entries = 0;
        for (hudson.scm.ChangeLogSet.Entry entry : run.getChangeSets().get(0)) {
            assertNull("core gives an entry no id of its own", entry.getCommitId());
            entries++;
        }
        assertEquals("the change log had two entries", 2, entries);
        assertEquals("the build must be stored even though its changes carry no id",
                1, count("SELECT COUNT(*) FROM builds WHERE job_name = 'no-commit-ids'"));
    }

    @Test
    public void entriesWithoutACommitIdAreLeftOut() throws Exception {
        buildWithTwoIdlessChanges("skipped-commits");

        assertEquals("the build is there", 1,
                count("SELECT COUNT(*) FROM builds WHERE job_name = 'skipped-commits'"));
        assertEquals("an entry with no id is not a commit row", 0, count("SELECT COUNT(*) FROM commits"));
    }

    @Test
    public void reRecordingSuchABuildStillKeepsOneRow() throws Exception {
        FreeStyleBuild run = buildWithTwoIdlessChanges("re-recorded");
        BuildRecorder.record(run);

        assertEquals("re-recording is still an upsert on the same build",
                1, count("SELECT COUNT(*) FROM builds WHERE job_name = 're-recorded'"));
    }

    private int count(String sql) throws Exception {
        String db = new java.io.File(j.jenkins.getRootDir(), "pipeline-dora-metrics/metrics.db").getAbsolutePath();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
