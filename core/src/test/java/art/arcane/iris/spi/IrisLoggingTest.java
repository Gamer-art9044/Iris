package art.arcane.iris.spi;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class IrisLoggingTest {
    @Before
    public void resetBinding() {
        IrisPlatforms.unbind();
    }

    @After
    public void clearBinding() {
        IrisPlatforms.unbind();
    }

    @Test
    public void contextualReportPrintsFullStacktraceWithBoundPlatform() {
        IrisPlatform platform = mock(IrisPlatform.class, CALLS_REAL_METHODS);
        IrisPlatforms.bind(platform);
        IllegalStateException failure = new IllegalStateException("outer", new IllegalArgumentException("inner"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(output, true, StandardCharsets.UTF_8));
        try {
            IrisLogging.reportError("Runtime world creation failed.", failure);
        } finally {
            System.setErr(originalErr);
        }

        verify(platform).log(LogLevel.ERROR, "Runtime world creation failed.");
        verify(platform).reportError("Runtime world creation failed.", failure);
        verify(platform).reportError(failure);
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("IllegalStateException"));
        assertTrue(text.contains("IllegalArgumentException"));
        assertTrue(text.contains("inner"));
    }

    @Test
    public void contextualReportHonorsPlatformOverrideSuppression() {
        IrisPlatform platform = mock(IrisPlatform.class);
        IrisPlatforms.bind(platform);
        IllegalStateException failure = new IllegalStateException("suppressed");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(output, true, StandardCharsets.UTF_8));
        try {
            IrisLogging.reportError("Throttled failure.", failure);
        } finally {
            System.setErr(originalErr);
        }

        verify(platform).reportError("Throttled failure.", failure);
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("suppressed"));
    }
}
