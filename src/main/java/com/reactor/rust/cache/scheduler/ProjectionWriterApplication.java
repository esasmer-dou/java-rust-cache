package com.reactor.rust.cache.scheduler;

import com.reactor.rust.cache.config.CacheProperties;
import com.reactor.rust.cache.projection.CacheWriterProjectionSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process lifecycle for a bounded projection refresh scheduler.
 */
public final class ProjectionWriterApplication {

    private ProjectionWriterApplication() {}

    public static Builder from(CacheProperties properties, String rootPrefix) {
        Objects.requireNonNull(properties, "properties");
        String prefix = requireText(rootPrefix, "rootPrefix");
        return new Builder()
                .settings(CacheWriterProjectionSettings.resolveAll(properties, prefix))
                .schedulerThreads(properties.getInt(prefix + ".scheduler-threads"))
                .runOnce(properties.getBoolean(prefix + ".run-once"));
    }

    public static Builder builder() {
        return new Builder();
    }

    @FunctionalInterface
    public interface Module {
        void configure(ModuleContext context);
    }

    public static final class ModuleContext {

        private final Builder builder;

        private ModuleContext(Builder builder) {
            this.builder = builder;
        }

        public <T extends AutoCloseable> T manage(T resource) {
            T managed = Objects.requireNonNull(resource, "resource");
            builder.resources.add(managed);
            return managed;
        }

        public ModuleContext refresher(ProjectionRefreshScheduler.ProjectionRefresher refresher) {
            builder.refresher(refresher);
            return this;
        }
    }

    public static final class Builder {

        private List<CacheWriterProjectionSettings> settings = List.of();
        private ProjectionRefreshScheduler.ProjectionRefresher refresher;
        private final List<AutoCloseable> resources = new ArrayList<>();
        private final List<Module> modules = new ArrayList<>();
        private boolean runOnce;
        private int schedulerThreads = 1;
        private long schedulerThreadStackBytes = 256L * 1024L;
        private long firstRunTimeoutMillis = 60_000L;
        private String threadNamePrefix = "cache-writer";
        private String shutdownThreadName = "cache-writer-shutdown";
        private ProjectionRefreshObserver observer = ProjectionRefreshObserver.console();
        private boolean started;

        public Builder settings(List<CacheWriterProjectionSettings> settings) {
            this.settings = List.copyOf(Objects.requireNonNull(settings, "settings"));
            return this;
        }

        public Builder refresher(ProjectionRefreshScheduler.ProjectionRefresher refresher) {
            this.refresher = Objects.requireNonNull(refresher, "refresher");
            return this;
        }

        public Builder manage(AutoCloseable... managedResources) {
            if (managedResources != null) {
                for (AutoCloseable resource : managedResources) {
                    resources.add(Objects.requireNonNull(resource, "resource"));
                }
            }
            return this;
        }

        public Builder module(Module module) {
            modules.add(Objects.requireNonNull(module, "module"));
            return this;
        }

        public Builder runOnce(boolean runOnce) {
            this.runOnce = runOnce;
            return this;
        }

        public Builder schedulerThreads(int schedulerThreads) {
            this.schedulerThreads = schedulerThreads;
            return this;
        }

        public Builder schedulerThreadStackBytes(long schedulerThreadStackBytes) {
            this.schedulerThreadStackBytes = schedulerThreadStackBytes;
            return this;
        }

        public Builder firstRunTimeoutMillis(long firstRunTimeoutMillis) {
            this.firstRunTimeoutMillis = firstRunTimeoutMillis;
            return this;
        }

        public Builder threadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = requireText(threadNamePrefix, "threadNamePrefix");
            return this;
        }

        public Builder shutdownThreadName(String shutdownThreadName) {
            this.shutdownThreadName = requireText(shutdownThreadName, "shutdownThreadName");
            return this;
        }

        public Builder observer(ProjectionRefreshObserver observer) {
            this.observer = Objects.requireNonNull(observer, "observer");
            return this;
        }

        public void run() {
            try (RunningWriter writer = start()) {
                if (runOnce) {
                    writer.awaitFirstRun();
                } else {
                    try {
                        writer.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        public synchronized RunningWriter start() {
            if (started) {
                throw new IllegalStateException("Projection writer application builder can only start once");
            }
            started = true;
            RunningWriter writer = null;
            try {
                ModuleContext context = new ModuleContext(this);
                for (Module module : modules) {
                    module.configure(context);
                }
                ProjectionRefreshScheduler scheduler = ProjectionRefreshScheduler.builder()
                        .settings(settings)
                        .refresher(refresher)
                        .schedulerThreads(schedulerThreads)
                        .schedulerThreadStackBytes(schedulerThreadStackBytes)
                        .runOnce(runOnce)
                        .firstRunTimeoutMillis(firstRunTimeoutMillis)
                        .threadNamePrefix(threadNamePrefix)
                        .observer(observer)
                        .build();
                writer = RunningWriter.create(
                        shutdownThreadName,
                        scheduler,
                        List.copyOf(resources));
                writer.start();
                return writer;
            } catch (RuntimeException | Error startupFailure) {
                if (writer == null) {
                    closeResourcesAfterFailure(resources, startupFailure);
                } else {
                    writer.closeAfterFailure(startupFailure);
                }
                throw startupFailure;
            }
        }

        private static void closeResourcesAfterFailure(
                List<AutoCloseable> resources,
                Throwable startupFailure) {
            for (int index = resources.size() - 1; index >= 0; index--) {
                try {
                    resources.get(index).close();
                } catch (Exception | LinkageError closeFailure) {
                    startupFailure.addSuppressed(closeFailure);
                }
            }
        }
    }

    public static final class RunningWriter implements AutoCloseable {

        private static final System.Logger LOG = System.getLogger(RunningWriter.class.getName());
        private final ProjectionRefreshScheduler scheduler;
        private final List<AutoCloseable> resources;
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final AtomicBoolean stopping = new AtomicBoolean();
        private final Thread shutdownHook;
        private boolean hookRegistered;

        private RunningWriter(
                String shutdownThreadName,
                ProjectionRefreshScheduler scheduler,
                List<AutoCloseable> resources) {
            this.scheduler = scheduler;
            this.resources = resources;
            this.shutdownHook = new Thread(() -> shutdown(false), shutdownThreadName);
        }

        private static RunningWriter create(
                String shutdownThreadName,
                ProjectionRefreshScheduler scheduler,
                List<AutoCloseable> resources) {
            return new RunningWriter(shutdownThreadName, scheduler, resources);
        }

        private void start() {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            hookRegistered = true;
            scheduler.start();
        }

        public void await() throws InterruptedException {
            stopped.await();
        }

        public void awaitFirstRun() {
            scheduler.awaitFirstRun();
        }

        public ProjectionRefreshScheduler scheduler() {
            return scheduler;
        }

        private void closeAfterFailure(Throwable startupFailure) {
            try {
                close();
            } catch (RuntimeException closeFailure) {
                startupFailure.addSuppressed(closeFailure);
            }
        }

        @Override
        public void close() {
            shutdown(true);
        }

        private void shutdown(boolean removeHook) {
            if (!stopping.compareAndSet(false, true)) {
                return;
            }
            if (removeHook && hookRegistered) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // JVM shutdown is already running; the hook owns cleanup.
                }
            }
            try {
                close("scheduler", scheduler);
                for (int index = resources.size() - 1; index >= 0; index--) {
                    close("resource", resources.get(index));
                }
            } finally {
                stopped.countDown();
            }
        }

        private static void close(String name, AutoCloseable resource) {
            try {
                resource.close();
            } catch (Exception | LinkageError failure) {
                LOG.log(System.Logger.Level.ERROR, "Projection writer " + name + " close failed", failure);
            }
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
