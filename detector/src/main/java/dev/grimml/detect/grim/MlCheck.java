package dev.grimml.detect.grim;

import ac.grim.grimac.api.AbstractCheck;

/**
 * This is the ML check class for the GrimMLDetector.
 * It is a check that is used to count the violations of the ML checks.
 * It is shaped like a Grim check so Grim's FlagEvent listeners see it like any other check.
 * Grim's API has no way to add third-party checks to its own check list, so violations are counted here.
 */
public final class MlCheck implements AbstractCheck {

    private final String name;
    private final String description;
    private final double decayPerMinute;
    private double violations;
    private long lastViolation;
    private boolean enabled = true;

    public MlCheck(String name, String description, double decayPerMinute) {
        this.name = name;
        this.description = description;
        this.decayPerMinute = decayPerMinute;
    }

    // adds one violation (after decay) and returns the new count
    public synchronized double flag(long nowMs) {
        decay(nowMs);
        violations += 1.0;
        lastViolation = nowMs;
        return violations;
    }

    // decays the violations by the decay per minute
    private void decay(long nowMs) {
        if (lastViolation != 0 && decayPerMinute > 0) {
            double minutes = (nowMs - lastViolation) / 60_000.0;
            violations = Math.max(0.0, violations - minutes * decayPerMinute);
        }
    }

    @Override
    public String getCheckName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getStableKey() {
        return "grimml:" + name.toLowerCase();
    }

    @Override
    public synchronized double getViolations() {
        return violations;
    }

    @Override
    public synchronized long getLastViolationTime() {
        return lastViolation;
    }

    @Override
    public double getDecay() {
        return decayPerMinute;
    }

    @Override
    public double getSetbackVL() {
        return Double.MAX_VALUE; // we never set players back
    }

    @Override
    public boolean isExperimental() {
        return true;
    }

    @Override
    public String getConfigName() {
        return name;
    }

    @Override
    public void reload() {
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
