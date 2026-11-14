package dev.grimml.grim;

public final class CheckWeight {

    public final String name;
    public final double weight;
    public final CheckCategory category;
    public final double trust;

    public CheckWeight(String name, double weight, CheckCategory category, double trust) {
        this.name = name;
        this.weight = weight;
        this.category = category == null ? CheckCategory.PACKET : category;
        this.trust = trust;
    }
}
