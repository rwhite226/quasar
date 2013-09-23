/*
 * Copyright (C) 2013, Parallel Universe Software Co. All rights reserved.
 * 
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *  
 *   or (per the licensee's choosing)
 *  
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
/*
 * Based on code: 
 */
/*
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */
package co.paralleluniverse.fibers;

import co.paralleluniverse.concurrent.util.SingleConsumerNonblockingProducerDelayQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class FiberTimedScheduler {
    private static final AtomicInteger nameSuffixSequence = new AtomicInteger();
    private final Thread worker;
    private final SingleConsumerNonblockingProducerDelayQueue<ScheduledFutureTask> workQueue;
    private static final int RUNNING = 0;
    private static final int SHUTDOWN = 1;
    private static final int STOP = 1;
    private static final int TERMINATED = 2;
    private volatile int state = RUNNING;
    private final ReentrantLock mainLock = new ReentrantLock();

    public FiberTimedScheduler(ThreadFactory threadFactory) {
        this.worker = threadFactory.newThread(new Runnable() {
            @Override
            public void run() {
                work();
            }
        });
        this.workQueue = new SingleConsumerNonblockingProducerDelayQueue<ScheduledFutureTask>();
        worker.start();
    }

    public FiberTimedScheduler() {
        this(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "single-threaded-scheduled-executor-" + nameSuffixSequence.incrementAndGet());
            }
        });
    }

    private void work() {
        try {
            while (state == RUNNING) {
                try {
                    ScheduledFutureTask task = workQueue.take();
                    task.run();
                } catch (InterruptedException e) {
                    if (state != RUNNING) {
                        state = STOP;
                        break;
                    }
                }
            }

            if (state == SHUTDOWN) {
                while (state < STOP && !workQueue.isEmpty()) {
                    try {
                        ScheduledFutureTask task = workQueue.take();
                        task.run();
                    } catch (InterruptedException e) {
                        if (state != RUNNING) {
                            state = STOP;
                            break;
                        }
                    }
                }
            }
        } finally {
            state = TERMINATED;
        }
    }
    /**
     * Sequence number to break scheduling ties, and in turn to
     * guarantee FIFO order among tied entries.
     */
    private final AtomicLong sequencer = new AtomicLong();

    /**
     * Returns current nanosecond time.
     */
    final long now() {
        return System.nanoTime();
    }

    private class ScheduledFutureTask implements Delayed, Runnable, Future<Void> {
        private final Fiber<?> fiber;
        /**
         * Sequence number to break ties FIFO
         */
        private final long sequenceNumber;
        /**
         * The time the task is enabled to execute in nanoTime units
         */
        private long time;
        private volatile boolean cancelled = false;
        private long delay;

        /**
         * Creates a one-shot action with given nanoTime-based trigger time.
         */
        ScheduledFutureTask(Fiber<?> fiber, long ns) {
            this.fiber = fiber;
            this.time = ns;
            this.sequenceNumber = sequencer.getAndIncrement();
        }

        @Override
        public long getDelay(TimeUnit unit) {
            final long d = unit.convert(time - now(), NANOSECONDS);
            this.delay = d;
            return d;
        }

        @Override
        public int compareTo(Delayed other) {
            if (other == this) // compare zero if same object
                return 0;
            final ScheduledFutureTask x = (ScheduledFutureTask) other;
            final long diff = time - x.time;
            if (diff < 0)
                return -1;
            else if (diff > 0)
                return 1;
            else if (sequenceNumber < x.sequenceNumber)
                return -1;
            else
                return 1;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            this.cancelled = true;
            return true;
        }

        @Override
        public void run() {
            if (!cancelled) {
                try {
                    if (fiber.getMonitor() != null)
                        fiber.getMonitor().timedParkLatency(-delay);
                    fiber.unpark();
                } catch (Exception e) {
                }
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Void get() throws InterruptedException, ExecutionException {
            throw new UnsupportedOperationException();
        }

        @Override
        public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * State check needed by ScheduledThreadPoolExecutor to
     * enable running tasks during shutdown.
     *
     * @param shutdownOK true if should return true if SHUTDOWN
     */
    final boolean isRunningOrShutdown(boolean shutdownOK) {
        int rs = state;
        return rs == RUNNING || (rs == SHUTDOWN && shutdownOK);
    }

    /**
     * Main execution method for delayed or periodic tasks. If pool
     * is shut down, rejects the task. Otherwise adds task to queue
     * and starts a thread, if necessary, to run it. (We cannot
     * prestart the thread to run the task because the task (probably)
     * shouldn't be run yet.) If the pool is shut down while the task
     * is being added, cancel and remove it if required by state and
     * run-after-shutdown parameters.
     *
     * @param task the task
     */
    private void delayedExecute(ScheduledFutureTask task) {
        if (isShutdown())
            reject(task);
        else
            workQueue.add(task);
    }

    protected void reject(Runnable command) {
        throw new RejectedExecutionException("Task " + command + " rejected from " + this);
    }

    /**
     * Returns the trigger time of a delayed action.
     */
    private long triggerTime(long delay, TimeUnit unit) {
        return triggerTime(unit.toNanos((delay < 0) ? 0 : delay));
    }

    /**
     * Returns the trigger time of a delayed action.
     */
    private long triggerTime(long delay) {
        return now() + ((delay < (Long.MAX_VALUE >> 1)) ? delay : overflowFree(delay));
    }

    /**
     * Constrains the values of all delays in the queue to be within
     * Long.MAX_VALUE of each other, to avoid overflow in compareTo.
     * This may occur if a task is eligible to be dequeued, but has
     * not yet been, while some other task is added with a delay of
     * Long.MAX_VALUE.
     */
    private long overflowFree(long delay) {
        Delayed head = workQueue.peek();
        if (head != null) {
            long headDelay = head.getDelay(NANOSECONDS);
            if (headDelay < 0 && (delay - headDelay < 0))
                delay = Long.MAX_VALUE + headDelay;
        }
        return delay;
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    public Future<Void> schedule(Fiber<?> fiber, long delay, TimeUnit unit) {
        if (fiber == null || unit == null)
            throw new NullPointerException();
        ScheduledFutureTask t = new ScheduledFutureTask(fiber, triggerTime(delay, unit));
        delayedExecute(t);
        return t;
    }

    /**
     * Initiates an orderly shutdown in which previously submitted
     * tasks are executed, but no new tasks will be accepted.
     * Invocation has no additional effect if already shut down.
     *
     * <p>This method does not wait for previously submitted tasks to
     * complete execution. Use {@link #awaitTermination awaitTermination}
     * to do that.
     *
     * <p>If the {@code ExecuteExistingDelayedTasksAfterShutdownPolicy}
     * has been set {@code false}, existing delayed tasks whose delays
     * have not yet elapsed are cancelled. And unless the {@code
     * ContinueExistingPeriodicTasksAfterShutdownPolicy} has been set
     * {@code true}, future executions of existing periodic tasks will
     * be cancelled.
     *
     * @throws SecurityException {@inheritDoc}
     */
    public void shutdown() {
        assert false;
        mainLock.lock();
        try {
            if (state < SHUTDOWN)
                state = SHUTDOWN;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Attempts to stop all actively executing tasks, halts the
     * processing of waiting tasks, and returns a list of the tasks
     * that were awaiting execution.
     *
     * <p>This method does not wait for actively executing tasks to
     * terminate. Use {@link #awaitTermination awaitTermination} to
     * do that.
     *
     * <p>There are no guarantees beyond best-effort attempts to stop
     * processing actively executing tasks. This implementation
     * cancels tasks via {@link Thread#interrupt}, so any task that
     * fails to respond to interrupts may never terminate.
     *
     * @return list of tasks that never commenced execution.
     * Each element of this list is a {@link ScheduledFuture},
     * including those tasks submitted using {@code execute},
     * which are for scheduling purposes used as the basis of a
     * zero-delay {@code ScheduledFuture}.
     * @throws SecurityException {@inheritDoc}
     */
    public List<Runnable> shutdownNow() {
        assert false;
        mainLock.lock();
        try {
            if (state < STOP)
                state = STOP;
            worker.interrupt();
            List<Runnable> list = new ArrayList<Runnable>();
            workQueue.drainTo(list);
            return list;
        } finally {
            mainLock.unlock();
        }
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        long millis = TimeUnit.MILLISECONDS.convert(nanos, TimeUnit.NANOSECONDS);
        worker.join(millis, (int) (nanos - millis));
        return !worker.isAlive();
    }

    public boolean isShutdown() {
        return state >= SHUTDOWN;
    }

    public boolean isTerminated() {
        return !worker.isAlive();
    }
}