package art.arcane.iris.engine.object;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class IrisDimensionRuntimeContractTest {
    @Test
    public void exactTypeAndHeightContractPasses() {
        IrisDimensionRuntimeContract expected = expectedContract();
        IrisDimensionRuntimeContract actual = new IrisDimensionRuntimeContract("iris:overworld", -256, 768, 512);

        expected.requireExact("test world", actual);
        expected.requireHeight("test world", -256, 768);
    }

    @Test
    public void tallPackOnVanillaHeightWorldIsRejected() {
        IrisDimensionRuntimeContract expected = expectedContract();
        IrisDimensionRuntimeContract actual = new IrisDimensionRuntimeContract("minecraft:overworld", -64, 384, 384);

        try {
            expected.requireExact("Bukkit world 'world'", actual);
            fail("Vanilla height world must be rejected");
        } catch (IrisDimensionContractException e) {
            assertTrue(e.getMessage().contains("Bukkit world 'world'"));
            assertTrue(e.getMessage().contains("iris:overworld"));
            assertTrue(e.getMessage().contains("-256..512"));
            assertTrue(e.getMessage().contains("minecraft:overworld"));
            assertTrue(e.getMessage().contains("-64..320"));
            assertTrue(e.getMessage().contains("Generation was refused"));
        }
    }

    @Test
    public void wrongIrisTypeKeyIsRejectedEvenWhenHeightMatches() {
        IrisDimensionRuntimeContract expected = expectedContract();
        IrisDimensionRuntimeContract actual = new IrisDimensionRuntimeContract("iris:other", -256, 768, 512);

        try {
            expected.requireExact("test world", actual);
            fail("Wrong Iris dimension type must be rejected");
        } catch (IrisDimensionContractException e) {
            assertTrue(e.getMessage().contains("iris:other"));
        }
    }

    @Test
    public void logicalHeightMismatchIsRejected() {
        IrisDimensionRuntimeContract expected = expectedContract();
        IrisDimensionRuntimeContract actual = new IrisDimensionRuntimeContract("iris:overworld", -256, 768, 384);

        try {
            expected.requireExact("test world", actual);
            fail("Wrong logical height must be rejected");
        } catch (IrisDimensionContractException e) {
            assertTrue(e.getMessage().contains("logical height 384"));
        }
    }

    @Test
    public void hotloadMismatchReportsReplacementAgainstLiveRuntime() {
        IrisDimension active = new IrisDimension();
        active.setLoadKey("overworld");
        active.setDimensionHeight(new IrisRange(-256, 512));
        active.setLogicalHeight(512);
        IrisDimension replacement = new IrisDimension();
        replacement.setLoadKey("overworld");
        replacement.setDimensionHeight(new IrisRange(-384, 640));
        replacement.setLogicalHeight(640);

        try {
            IrisDimensionRuntimeContract.requireHotloadCompatible("Studio world", active, replacement, "iris");
            fail("A hotload cannot replace the live vertical contract");
        } catch (IrisDimensionContractException e) {
            assertTrue(e.getMessage().contains("requires Iris dimension type iris:overworld with range -384..640"));
            assertTrue(e.getMessage().contains("loaded runtime uses iris:overworld with range -256..512"));
        }
    }

    private IrisDimensionRuntimeContract expectedContract() {
        IrisDimension dimension = new IrisDimension();
        dimension.setLoadKey("overworld");
        dimension.setDimensionHeight(new IrisRange(-256, 512));
        dimension.setLogicalHeight(512);
        return IrisDimensionRuntimeContract.expected(dimension, "iris");
    }
}
