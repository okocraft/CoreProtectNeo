package net.coreprotect.consumer;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import net.coreprotect.consumer.process.Process;
import net.coreprotect.database.Database;
import net.coreprotect.neoforge.NeoPlatform;
import net.coreprotect.utility.ErrorReporter;

/**
 * Consumer queue engine.
 *
 * <p>Actions recorded by the listeners are pushed into a queue and written to the database by a
 * background thread in batches, exactly like the original plugin. The world is never blocked by
 * database work.
 */
public class Consumer {

    /** Batch size of a single database transaction. */
    private static final int BATCH_SIZE = 1000;
    /** Maximum number of entries drained per transaction. */
    private static final int DRAIN_SIZE = 1000;

    public static volatile boolean transacting = false;
    public static volatile boolean interrupt = false;
    public static volatile boolean isPaused = false;

    private static final LinkedBlockingDeque<ConsumerEntry> QUEUE = new LinkedBlockingDeque<>();
    private static Thread consumerThread;
    private static volatile boolean running = false;

    private Consumer() {
        throw new IllegalStateException("Consumer class");
    }

    public static void initialize() {
        QUEUE.clear();
    }

    public static void startConsumer() {
        if (running) {
            return;
        }
        running = true;
        consumerThread = new Thread(Consumer::consume, "CoreProtect-Neo Consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
    }

    /** Stops the consumer thread, flushing everything that is still queued. */
    public static void stopConsumer() {
        running = false;
        Thread thread = consumerThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(15000L);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flush();
    }

    public static void add(ConsumerEntry entry) {
        if (entry != null && NeoPlatform.isServerRunning()) {
            QUEUE.add(entry);
        }
    }

    public static int queueSize() {
        return QUEUE.size();
    }

    private static void consume() {
        while (running) {
            try {
                if (isPaused) {
                    Thread.sleep(250L);
                    continue;
                }

                ConsumerEntry first = QUEUE.poll(500L, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }

                List<ConsumerEntry> batch = new ArrayList<>(DRAIN_SIZE);
                batch.add(first);
                QUEUE.drainTo(batch, DRAIN_SIZE - 1);
                if (!writeBatch(batch)) {
                    returnBatch(batch);
                    Thread.sleep(1000L);
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            catch (Throwable throwable) {
                ErrorReporter.report(throwable);
            }
        }
    }

    /** Writes everything currently queued (used on shutdown and by the reload command). */
    public static void flush() {
        List<ConsumerEntry> batch = new ArrayList<>(DRAIN_SIZE);
        while (!QUEUE.isEmpty()) {
            batch.clear();
            QUEUE.drainTo(batch, DRAIN_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            try {
                if (!writeBatch(batch)) {
                    returnBatch(batch);
                    break;
                }
            }
            catch (Throwable throwable) {
                ErrorReporter.report(throwable);
                break;
            }
        }
    }

    private static void returnBatch(List<ConsumerEntry> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) {
            QUEUE.addFirst(batch.get(i));
        }
    }

    private static boolean writeBatch(List<ConsumerEntry> batch) {
        Connection connection = null;
        try {
            connection = Database.getConnection(true);
            if (connection == null) {
                return false;
            }

            final boolean isMySQL = net.coreprotect.config.Config.getGlobal().MYSQL;
            connection.setAutoCommit(false);
            transacting = true;

            Process.process(connection, batch, BATCH_SIZE);

            connection.commit();
            return true;
        }
        catch (Throwable throwable) {
            ErrorReporter.report(throwable);
            if (connection != null) {
                try {
                    connection.rollback();
                }
                catch (Exception ignored) {
                    // ignore
                }
            }
            return false;
        }
        finally {
            transacting = false;
            if (connection != null) {
                try {
                    connection.setAutoCommit(true);
                    connection.close();
                }
                catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }
}
