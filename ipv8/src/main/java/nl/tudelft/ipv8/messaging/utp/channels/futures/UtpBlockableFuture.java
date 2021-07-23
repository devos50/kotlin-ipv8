package nl.tudelft.ipv8.messaging.utp.channels.futures;

import java.io.IOException;
import java.util.concurrent.Semaphore;

public class UtpBlockableFuture {
    protected volatile boolean isDone;
    protected volatile IOException exception;
    protected final Semaphore semaphore = new Semaphore(1);

    public UtpBlockableFuture() throws InterruptedException {
        semaphore.acquire();
    }
    /**
     * Returns true if this future task succeeded.
     */
    public boolean isSuccessful() {
        return exception == null;
    }

    /**
     * Blocks the current thread until the future task is done.
     */
    public void block() throws InterruptedException {
        semaphore.acquire();
        semaphore.release();
    }

    public boolean isDone() {
        return isDone;
    }

}
