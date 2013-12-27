package com.indeed.status.core;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.indeed.util.varexport.Export;
import com.indeed.util.varexport.VarExporter;
import org.apache.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.PreDestroy;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.TimeUnit;

/*import com.indeed.common.util.Terminable; todo(cameron)*/

/**
 * The {@link DependencyManager} is a singleton clearinghouse responsible for knowing
 *  all dependencies of the system and their current availability.
 *
 *
 */
public class DependencyManager implements StatusUpdateProducer, StatusUpdateListener/*, Terminable todo(cameron)*/ {
    public static class Qualifiers {
        protected Qualifiers () { throw new UnsupportedOperationException("ResultType is a constants class."); }

        public static final String LIVE = "live";
        public static final String BACKGROUND = "bkgd"; // NOTE: Varying slightly from the "background" string used by client projects
                                                        //  just so we don't trip somebody up.
    }

    // TODO Some day, all of this will be replaced with a builder.

    public DependencyManager() {
        this(null, null, newDefaultThreadPool());
    }
    public DependencyManager(final String appName) {
        this(appName, null, newDefaultThreadPool());
    }
    public DependencyManager ( final String appName, final Logger logger ) {
        this(appName, logger, newDefaultThreadPool());
    }
    public DependencyManager ( final Logger logger ) {
        this(null, logger, newDefaultThreadPool());
    }

    public DependencyManager(
            @Nullable final String appName,
            @Nullable final Logger logger,
            @Nonnull final DependencyChecker checker
    ) {
        this(appName, logger, newDefaultThreadPool(),checker);
    }

    public DependencyManager(
            @Nullable final String appName,
            @Nullable final Logger logger,
            @Nonnull final ThreadPoolExecutor threadPool
    ) {
        this(appName, logger, threadPool, DependencyChecker.newBuilder()
                .setExecutorService(threadPool)
                .setLogger(logger)
                .build());
    }

    public DependencyManager(
            @Nullable final String appName,
            @Nullable final Logger logger,
            @Nonnull final ThreadPoolExecutor threadPool,
            @Nonnull final DependencyChecker checker
    ) {
        this.appName = resolveAppName(appName);
        this.log = null == logger ? Logger.getLogger(getClass()) : logger;

        this.executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder()
                .setNameFormat("dependency-management-thread-%d")
                .setDaemon(true)
                .setUncaughtExceptionHandler(new UncaughtExceptionHandler() {
                    @Override
                    public void uncaughtException(Thread t, Throwable e) {
                        log.error("Uncaught throwable in thread " + t.getName() + "/" + t.getId(), e);
                    }
                })
                .build()
        );

        this.threadPool = threadPool;

        this.checker = checker;

        VarExporter.forNamespace(getClass().getSimpleName()).includeInGlobal().export(this, "");
    }

    @Nullable
    public String getAppName() {
        return appName;
    }

    @Nullable
    protected String resolveAppName(@Nullable final String explicitAppName) {
        if (!Strings.isNullOrEmpty(explicitAppName)) {
            return explicitAppName;
        }

        final Supplier<VersionData> versionDataSupplier = getVersionDataSupplier();
        //noinspection ConstantConditions
        if (null != versionDataSupplier) {
            @Nullable final VersionData versionData = versionDataSupplier.get();

            if (null != versionData) {
                final String indeedLoggingAppName = versionData.getProjectName();
                if (!Strings.isNullOrEmpty(indeedLoggingAppName)) {
                    return indeedLoggingAppName;
                }
            }
        }

        return null;
    }

    @Nullable
    protected Supplier<VersionData> getVersionDataSupplier() {
        return null;
    }

    static ThreadPoolExecutor newDefaultThreadPool() {
        final ThreadPoolExecutor result = new ThreadPoolExecutor(
                // Bound the pool. Most foreground dependency managers should be called only very rarely, so
                //  keep a minimal core pool around and only grow it on demand.
                1, 16,
                // Threads will be kept alive in an idle state for a minute or two. After that, they may be
                //  garbage-collected, so that we're keeping a larger thread pool only during weird periods of
                //  congestion. (Note: the background manager will typically keep all threads pretty active, since it's
                //  repeatedly launching new pingers. The live manager will spin them up and down based on traffic to
                //  the rather uncommonly used /healthcheck/live uri).
                30, TimeUnit.SECONDS,
                // Use a blocking queue just to keep track of checks when the world is going wrong. This is mostly useful
                //  when we're adding a bunch of checks at the same time, such as during a live healthcheck. Might as well
                //  keep this pretty small, because any nontrivial wait to execute is going to blow up a timeout anyway.
                new SynchronousQueue<Runnable>(),
                // Name your threads.
                new ThreadFactoryBuilder()
                        .setNameFormat("dependency-default-checker-%d")
                        .setDaemon(true)
                        .setUncaughtExceptionHandler(new UncaughtExceptionHandler() {
                            @Override
                            public void uncaughtException(Thread t, Throwable e) {
                                Logger.getLogger(DependencyManager.class)
                                        .error("Uncaught throwable in thread " + t.getName() + "/" + t.getId(), e);
                            }
                        })
                        .build(),
                // Explicitly restating the default policy here, because healthchecks should Just Not Work if there
                //  are insufficient resources to support them. Given the smallish queue above, this means that
                //  we're going to end up throwing exceptions if we get too blocked up somehow.
                new AbortPolicy());

        result.prestartAllCoreThreads();

        return result;
    }

    @SuppressWarnings ( { "UnusedDeclaration" })
    public Collection<String> getDependencyIds() {
        return Collections.unmodifiableCollection(dependencies.keySet());
    }

    @Nonnull
    public CheckResultSet evaluate() {
        final CheckResultSet result = checker.evaluate(getDependencies());

        result.setAppName(this.appName);

        return result;
    }

    @Nullable
    public CheckResult evaluate (@Nonnull final String id) {
        final Dependency dependency = Preconditions.checkNotNull(dependencies.get(id), "Missing dependency '%s'", id);
        final CheckResultSet resultSet = checker.evaluate(Collections.singleton(dependency));

        resultSet.setAppName(this.appName);

        return resultSet.get(id);
    }

    /**
     * Launches a background pinger over the given dependency. The periodicity of the
     *  check is controlled by the dependency manager object.
     *
     * @param dependency
     */
    public void launchPinger(final Dependency dependency) {
        final DependencyPinger pinger = newPingerFor(dependency);

        // Add a listener so that objects that want to listen for updates to ANY dependency
        //  can do so. Note that this is done ONLY for background-pinger type dependency
        //  checks, because it makes less sense to monitor checks that are evaluated
        //  unpredictably.
        pinger.addListener(updateHandler);

        executor.scheduleWithFixedDelay(pinger, 0, pinger.getPingPeriod(), TimeUnit.MILLISECONDS);

        addDependency(pinger);
    }

    protected DependencyPinger newPingerFor (final Dependency dependency) {
        final DependencyPinger pinger;
        final long dependencyPingPeriod = dependency.getPingPeriod();
        if (dependencyPingPeriod <= 0 || dependencyPingPeriod == AbstractDependency.DEFAULT_PING_PERIOD) {
            log.info("Creating pinger with ping period " + this.pingPeriod);
            pinger = new DependencyPinger(threadPool, dependency, this.pingPeriod);

        } else {
            log.info("Creating pinger with ping period " + dependency.getPingPeriod());
            pinger = new DependencyPinger(threadPool, dependency);
        }
        return pinger;
    }

    public Dependency getDependency ( final String id ) {
        return dependencies.get(id);
    }

    public void addDependency(final Dependency dependency) {
        final Dependency existing = dependencies.putIfAbsent(dependency.getId(), dependency);

        Preconditions.checkState(
                null == existing,
                "Can't have two dependencies with the same ID [%s]. Check your setup.", dependency.getId());
    }

    public Collection<Dependency> getDependencies() {
        return Collections.unmodifiableCollection(dependencies.values());
    }

    @Override
    public void onChanged (@Nonnull final Dependency source, @Nullable final CheckResult original, @Nonnull final CheckResult updated) {
        updateHandler.onChanged(source, original, updated);
    }

    @Override
    public void clear () {
        updateHandler.clear();
    }

    @Override
    public void addListener (final StatusUpdateListener listener) {
        updateHandler.addListener(listener);
    }

    public void setPingPeriod(final long pingPeriod) {
        this.pingPeriod = pingPeriod;
    }

    /*@Override todo(cameron)*/
    @PreDestroy
    public void shutdown () {
        this.checker.shutdown();
        this.executor.shutdownNow();
    }

    @Export (name="active-threads")
    public int getActiveDependencyThreads() {
        return threadPool.getActiveCount();
    }

    @Export(name="core-pool-size")
    public int getCorePoolSize() {
        return threadPool.getCorePoolSize();
    }

    @Export(name="queue-size")
    public int getQueueSize() {
        final BlockingQueue<Runnable> queue = threadPool.getQueue();
        return null == queue ? 0 : queue.size();
    }

    private static final int DEFAULT_PING_PERIOD = 30 * 1000; // 30 seconds

    @SuppressWarnings ({ "FieldCanBeLocal" })
    @Nonnull
    private final Logger log;
    private long pingPeriod = DEFAULT_PING_PERIOD;
    @Nullable
    private final String appName;

    /// Timer for managing scheduled executions
    @Nonnull
    private final ScheduledExecutorService executor;
    /// Thread pool for running dependency checks
    @Nonnull
    private final ThreadPoolExecutor threadPool;

    /// Container for checking all dependencies
    @Nonnull
    private final DependencyChecker checker;

    /// Delegate for handling event propagation
    @Nonnull
    private final StatusUpdateDelegate updateHandler = new StatusUpdateDelegate();

    /// Collection of all dependencies governed by this manager. The keys of this map are the unique
    ///  String identifiers of each dependency. The values are the immutable objects representing the
    ///  canonical view of each dependency. This map does <em>not</em> indicate the current status
    ///  of dependencies, but rather the set of dependencies that are registered with the system.
    @Nonnull
    private ConcurrentMap<String, Dependency> dependencies = Maps.newConcurrentMap();
}
