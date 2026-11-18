package dev.grimml.grim;

import org.bukkit.configuration.file.YamlConfiguration;

public final class GrimWeightsSelfCheck {

    public static void main(String[] args) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("version", 1);
        y.set("defaults.unknown-weight", 0.0);
        y.set("checks.Reach.weight", 3.0);
        y.set("checks.Reach.category", "combat");
        y.set("checks.Reach.trust", 0.85);
        y.set("checks.GroundSpoof.weight", 0.5);
        y.set("checks.GroundSpoof.category", "movement");
        GrimWeights w = GrimWeightsLoader.parse(y);
        if (!w.of("reach").category.combatRelevant()) {
            throw new AssertionError("reach is combat");
        }
        if (w.of("reach").weight != 3.0) {
            throw new AssertionError("weight");
        }
        if (w.of("NoSuchCheck").weight != 0.0) {
            throw new AssertionError("unknown defaults to 0");
        }
        if (w.of("GroundSpoof").category != CheckCategory.MOVEMENT) {
            throw new AssertionError("movement");
        }
        System.out.println("grim weights self-check passed");
    }
}
