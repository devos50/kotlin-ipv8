package nl.tudelft.ipv8.messaging.utp.channels.impl.read;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.function.Consumer;

import nl.tudelft.ipv8.messaging.utp.channels.futures.UtpReadFuture;

public class UtpReadFutureImpl extends UtpReadFuture {

    public UtpReadFutureImpl() throws InterruptedException {
        super();
    }

    /**
     * Releasing semaphore and running the listener if set.
     */
    public void finished(IOException exp, ByteArrayOutputStream bos) {
        this.bos = bos;
        this.exception = exp;
        isDone = true;
        semaphore.release();
        listenerLock.lock();
        listenerLock.unlock();
    }
}
