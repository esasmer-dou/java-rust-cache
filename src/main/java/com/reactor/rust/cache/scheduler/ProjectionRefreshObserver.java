package com.reactor.rust.cache.scheduler;

/**
 * Receives low-frequency scheduler lifecycle events without imposing a logging dependency.
 */
public interface ProjectionRefreshObserver {

    default void onPublished(ProjectionRefreshResult result) {
    }

    default void onSkipped(ProjectionRefreshResult result, String lockName) {
    }

    default void onFailure(String projection, Throwable error) {
    }

    default void onConfigurationWarning(String warning, String phase) {
    }

    static ProjectionRefreshObserver console() {
        return ConsoleHolder.INSTANCE;
    }

    static ProjectionRefreshObserver noop() {
        return NoopHolder.INSTANCE;
    }

    final class ConsoleHolder {
        private static final ProjectionRefreshObserver INSTANCE = new ProjectionRefreshObserver() {
            @Override
            public void onPublished(ProjectionRefreshResult result) {
                System.out.println("cache refresh published projection=" + result.projection()
                        + " version=" + result.version()
                        + " keys=" + result.writtenKeys());
            }

            @Override
            public void onSkipped(ProjectionRefreshResult result, String lockName) {
                System.out.println("cache refresh skipped projection=" + result.projection()
                        + " lock=" + lockName
                        + " reason=another replica owns the Redis lock");
            }

            @Override
            public void onFailure(String projection, Throwable error) {
                System.err.println("cache refresh failed projection=" + projection
                        + ": " + error.getMessage());
                error.printStackTrace(System.err);
            }

            @Override
            public void onConfigurationWarning(String warning, String phase) {
                System.err.println("WARNING cache writer config " + warning + " phase=" + phase);
            }
        };

        private ConsoleHolder() {
        }
    }

    final class NoopHolder {
        private static final ProjectionRefreshObserver INSTANCE = new ProjectionRefreshObserver() {};

        private NoopHolder() {
        }
    }
}
