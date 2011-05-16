package net.sf.seide.controller.impl;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import net.sf.seide.controller.StageController;
import net.sf.seide.core.Dispatcher;
import net.sf.seide.core.JMXHelper;
import net.sf.seide.core.RuntimeStage;
import net.sf.seide.core.TimeoutEnabled;
import net.sf.seide.event.RunnableEventHandlerWrapper;
import net.sf.seide.message.Message;
import net.sf.seide.thread.DefaultThreadPoolExecutorFactory;
import net.sf.seide.thread.ThreadPoolExecutorFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * @author german.kondolf
 */
public class DirectThreadPoolStageController
    implements StageController {

    private static final Logger LOGGER = LoggerFactory.getLogger(DirectThreadPoolStageController.class);

    private static final String THREAD_POOL_EXECUTOR_MXBEAN_PREFIX = "net.sf.seide.thread:type=ThreadPoolExecutor,name=tpe-";

    private ThreadPoolExecutorFactory executorFactory = new DefaultThreadPoolExecutorFactory();

    private Dispatcher dispatcher;
    private RuntimeStage runtimeStage;
    private ExecutorService executor;

    private int timeout = -1;
    private int configuredTimeout = this.timeout;
    private int timeoutMonitorThreadCount = 1;
    private ScheduledThreadPoolExecutor timeoutMonitorExecutor = null;

    private volatile boolean started = false;
    private volatile boolean stopRequired = false;

    @Override
    public void execute(Message message) {
        RunnableEventHandlerWrapper runnable = new RunnableEventHandlerWrapper(this.dispatcher, this.runtimeStage, message);
        Future<?> future = this.executor.submit(runnable);

        this.handleTimeoutControl(message, future);
    }

    private void handleTimeoutControl(final Message message, final Future<?> future) {
        if (this.timeoutMonitorExecutor == null) {
            return;
        }

        int runningTimeout = this.evaluateTimeout(message);
        if (runningTimeout > 0 && !future.isDone() && !future.isCancelled()) {
            this.timeoutMonitorExecutor.schedule(new Runnable() {
                private final Future<?> internalFuture = future;
                private final Message internalData = message;

                public void run() {
                    if (!this.internalFuture.isDone() && !this.internalFuture.isCancelled()) {
                        this.internalFuture.cancel(true);
                        LOGGER.debug("Cancelling work @" + this.internalData.hashCode());
                    } else {
                        LOGGER.debug("Work @" + this.internalData.hashCode() + " was already cancelled/done!");
                    }
                }
            }, this.configuredTimeout, TimeUnit.MILLISECONDS);
        }
    }

    private int evaluateTimeout(Message message) {
        return (message instanceof TimeoutEnabled) ? ((TimeoutEnabled) message).getTimeoutInMillis()
            : this.configuredTimeout;
    }

    @Override
    public void start() {
        assert this.dispatcher != null : "Dispatcher must be specified.";
        assert this.runtimeStage != null : "RuntimeStage must be specified.";
        assert this.executorFactory != null : "ExecutorFactory must be specified.";
        assert this.timeoutMonitorThreadCount > 0 : "timeoutMonitorThreadCount must be > 0.";

        this.executor = this.executorFactory.create(this.dispatcher, this.runtimeStage);
        // register the JMX if applies
        if (JMXHelper.isThreadPoolExecutorJMXEnabled(this.executor)) {
            JMXHelper.registerMXBean(this.executor, THREAD_POOL_EXECUTOR_MXBEAN_PREFIX + this.dispatcher.getContext() + "-"
                + this.runtimeStage.getId());
        }

        if (this.timeout > 0) {
            this.timeoutMonitorExecutor = new ScheduledThreadPoolExecutor(this.timeoutMonitorThreadCount);
        } else {
            this.timeoutMonitorExecutor = null;
        }
        this.configuredTimeout = this.timeout;

        this.started = true;
    }

    @Override
    public void stop() {
        this.stopRequired = true;

        // shutdown the threadpool...
        List<Runnable> remaining = this.executor.shutdownNow();
        int remainingCount = remaining != null ? remaining.size() : 0;

        LOGGER.info("Shutdown processed for [" + this.runtimeStage.getId() + "], remaining runnables: " + remainingCount);

        // unregister TPE if applies
        if (JMXHelper.isThreadPoolExecutorJMXEnabled(this.executor)) {
            JMXHelper.unregisterMXBean(THREAD_POOL_EXECUTOR_MXBEAN_PREFIX + this.dispatcher.getContext() + "-"
                + this.runtimeStage.getId());
        }

        this.started = false;
        this.stopRequired = false;
    }

    @Override
    public Dispatcher getDispatcher() {
        return this.dispatcher;
    }

    @Override
    public void setDispatcher(Dispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void setRuntimeStage(RuntimeStage runtimeStage) {
        this.runtimeStage = runtimeStage;
        // this.executor = runtimeStage.getExecutor();
    }

    public void setExecutorFactory(ThreadPoolExecutorFactory executorFactory) {
        this.executorFactory = executorFactory;
    }

    public ThreadPoolExecutorFactory getExecutorFactory() {
        return this.executorFactory;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public void setTimeoutMonitorThreadCount(int timeoutMonitorThreadCount) {
        this.timeoutMonitorThreadCount = timeoutMonitorThreadCount;
    }

    @Override
    public boolean isRunning() {
        return this.started && !this.stopRequired;
    }

}
