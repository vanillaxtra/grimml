package dev.grimml.grim;

public final class FlagQueueSelfCheck {

    public static void main(String[] args) {
        FlagQueue q = new FlagQueue(4);
        FlagQueue.Slot out = new FlagQueue.Slot();
        for (int i = 0; i < 4; i++) {
            if (!q.offer(i, i, i, i, i, 20, 0, 0, 0, 0, 0, 0, 0, 0)) {
                throw new AssertionError("fit");
            }
        }
        if (q.offer(9, 9, 9, 9, 9, 0, 0, 0, 0, 0, 0, 0, 0, 0)) {
            throw new AssertionError("should drop");
        }
        if (q.drops() < 1) {
            throw new AssertionError("drop count");
        }
        if (!q.poll(out) || out.anonId != 0) {
            throw new AssertionError("order");
        }
        if (!q.offer(10, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0)) {
            throw new AssertionError("reuse after poll");
        }
        System.out.println("flag queue self-check passed drops=" + q.drops());
    }
}
