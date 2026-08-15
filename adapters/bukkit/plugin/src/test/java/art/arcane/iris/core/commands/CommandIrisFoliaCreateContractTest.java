package art.arcane.iris.core.commands;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class CommandIrisFoliaCreateContractTest {
    @Test
    public void ordinaryFoliaCreateRestartsOnlyAfterSuccessfulStagingAndFeedback() throws Exception {
        String source = Files.readString(Path.of(System.getProperty("iris.commandIrisSource")));
        String foliaCreate = source.substring(
                source.indexOf("if (J.isFolia()) {"),
                source.indexOf("        try {", source.indexOf("if (J.isFolia()) {"))
        );

        int stage = foliaCreate.indexOf("stageFoliaWorldCreation(worldName, dimension, seed)");
        int failureExit = foliaCreate.indexOf("if (!staged)");
        int feedback = foliaCreate.indexOf("COMMAND_IRIS_WORLD_STAGING_COMPLETED_RESTARTING_SERVER_GENERATE_LOAD");
        int restart = foliaCreate.indexOf("ServerConfigurator.restart(\"Iris staged Folia world");

        assertTrue(stage >= 0);
        assertTrue(stage < failureExit);
        assertTrue(failureExit < feedback);
        assertTrue(feedback < restart);
    }

    @Test
    public void foliaStagePublishesCurrentPaperDataBeforeRegisteringStartupAlias() throws Exception {
        String source = Files.readString(Path.of(System.getProperty("iris.commandIrisSource")));
        int methodStart = source.indexOf("private boolean stageFoliaWorldCreation(");
        int methodEnd = source.indexOf("private boolean registerWorldInBukkitYml(", methodStart);
        String staging = source.substring(methodStart, methodEnd);

        int currentPaperData = staging.indexOf("INMS.get().writeCurrentPaperWorldData(");
        int pack = staging.indexOf("installIntoWorld(");
        int publication = staging.indexOf("AtomicDirectoryPublisher.publishAbsent(");
        int registration = staging.indexOf("registerWorldInBukkitYml(worldKey");

        assertTrue(currentPaperData >= 0);
        assertTrue(currentPaperData < pack);
        assertTrue(pack < publication);
        assertTrue(publication < registration);
        assertTrue(source.contains("IrisWorldStorage.configuredWorldName("));
    }
}
