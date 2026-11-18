package dev.podjs.runtime;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Orders durable publications with revocation while socket cancellation stays independent. */
final class PodTransferFence {
    interface Transaction<T> { T run() throws Exception; }

    private final AtomicBoolean revoked = new AtomicBoolean();
    private final ReentrantLock commit = new ReentrantLock(true);

    boolean live() { return !revoked.get(); }
    void revoke() { revoked.set(true); }

    void check() throws IOException {
        if (!live()) throw new IOException("STALE_EPOCH");
    }

    <T> T publish(Transaction<T> transaction) throws Exception {
        commit.lockInterruptibly();
        try {
            check();
            return transaction.run();
        } finally {
            commit.unlock();
        }
    }

    void awaitRevoked() throws InterruptedException {
        if (live()) throw new IllegalStateException("Live transfer fence");
        commit.lockInterruptibly();
        commit.unlock();
    }
}
