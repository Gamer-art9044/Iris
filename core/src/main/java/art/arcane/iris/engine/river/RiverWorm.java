package art.arcane.iris.engine.river;

import java.util.List;

public record RiverWorm(
        String id,
        long seed,
        double weight,
        double wavelength,
        double detailWavelength,
        double tortuosity,
        double detailTortuosity,
        double maxOffset,
        int segments,
        double widthMultiplier,
        double bankMultiplier,
        double depthMultiplier,
        double bodyWavelength,
        double bodyDetailWavelength,
        double widthVariation,
        double bankVariation,
        double depthVariation,
        double roofVariation,
        int branchCap,
        double branchDecay,
        double confluenceMultiplier,
        double childChance,
        double branchChildChance,
        List<RiverWorm> children
) {
    public RiverWorm {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("id must use 1 to 64 lowercase letters, digits, underscores, or hyphens");
        }
        requireRange(weight, 0.000001D, 1000000D, "weight");
        requireRange(wavelength, 8D, 16384D, "wavelength");
        requireRange(detailWavelength, 8D, 16384D, "detailWavelength");
        requireRange(tortuosity, 0D, 1D, "tortuosity");
        requireRange(detailTortuosity, 0D, 1D, "detailTortuosity");
        requireRange(maxOffset, 0D, 1024D, "maxOffset");
        if (segments < 1 || segments > 64) {
            throw new IllegalArgumentException("segments must be between 1 and 64");
        }
        requireRange(widthMultiplier, 0.125D, 8D, "widthMultiplier");
        requireRange(bankMultiplier, 0.125D, 8D, "bankMultiplier");
        requireRange(depthMultiplier, 0.125D, 8D, "depthMultiplier");
        requireRange(bodyWavelength, 32D, 16384D, "bodyWavelength");
        requireRange(bodyDetailWavelength, 32D, 16384D, "bodyDetailWavelength");
        requireRange(widthVariation, 0D, 0.875D, "widthVariation");
        requireRange(bankVariation, 0D, 0.875D, "bankVariation");
        requireRange(depthVariation, 0D, 0.875D, "depthVariation");
        requireRange(roofVariation, 0D, 0.875D, "roofVariation");
        if (branchCap < 1 || branchCap > 8) {
            throw new IllegalArgumentException("branchCap must be between 1 and 8");
        }
        requireRange(branchDecay, 0D, 1D, "branchDecay");
        requireRange(confluenceMultiplier, 0D, 8D, "confluenceMultiplier");
        requireRange(childChance, 0D, 1D, "childChance");
        requireRange(branchChildChance, 0D, 1D, "branchChildChance");
        if (children == null) {
            throw new IllegalArgumentException("children must not be null");
        }
        if (children.size() > 16) {
            throw new IllegalArgumentException("children must contain at most 16 profiles");
        }
        for (RiverWorm child : children) {
            if (child == null) {
                throw new IllegalArgumentException("children must not contain null profiles");
            }
        }
        children = List.copyOf(children);
    }

    private static void requireRange(double value, double minimum, double maximum, String name) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }
}
