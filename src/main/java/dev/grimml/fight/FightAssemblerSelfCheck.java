package dev.grimml.fight;

public final class FightAssemblerSelfCheck {

    public static void main(String[] args) {
        if (FightAssembler.shouldFlush(true, false, true, 1L)) {
            throw new AssertionError("mark-only unmarked combat");
        }
        if (!FightAssembler.shouldFlush(true, true, false, 0L)) {
            throw new AssertionError("mark-only marked idle");
        }
        if (FightAssembler.includeNearby(true)) {
            throw new AssertionError("mark-only nearby");
        }
        if (!FightAssembler.shouldFlush(false, false, true, 1L)) {
            throw new AssertionError("auto combat");
        }
        if (FightAssembler.shouldFlush(false, false, false, 1L)) {
            throw new AssertionError("auto idle");
        }
        if (!FightAssembler.includeNearby(false)) {
            throw new AssertionError("auto nearby");
        }
        System.out.println("fight assembler self-check passed");
    }
}
