package moe.yushi.authlibinjector.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

public class AgentCompatibilityTest {
    @Test
    public void modLoaderCanInstallFactoryAfterPremain() throws Exception {
        runProbe("factory");
    }

    @Test
    public void authenticationUrlsAreStillTransformed() throws Exception {
        runProbe("redirect");
    }

    @Test
    public void localFiltersCanStillProxyUpstream() throws Exception {
        runProbe("proxy");
    }

    private void runProbe(String mode) throws Exception {
        String java = new File(System.getProperty("java.home"), "bin/java").getPath();
        String classes = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        String jar = System.getProperty("test.agentJar");
        // Используем настоящий -javaagent, отдельную JVM и метаданные без внешней сети.
        Process process = new ProcessBuilder(java,
                "--add-opens=java.base/sun.net.www.protocol.https=ALL-UNNAMED",
                "--add-opens=java.base/sun.net.www.protocol.http=ALL-UNNAMED",
                "-Dauthlibinjector.yggdrasil.prefetched=e30=",
                "-Dauthlibinjector.noLogFile=true",
                "-Dauthlibinjector.disableHttpd=true",
                "-javaagent:" + jar + "=https://auth.example/",
                "-cp", classes + File.pathSeparator + jar,
                "retroauth.probe.AgentProbe", mode)
                .redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Agent probe timed out: " + mode);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
        assertEquals(0, process.exitValue(), output.toString("UTF-8"));
    }
}
