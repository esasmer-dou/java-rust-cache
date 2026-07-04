package com.reactor.rust.cache.projection;

public interface ProjectionPropertySource {

    String get(String key);

    String getOptional(String key);

    String getRuntimeOverride(String key);

    String getFileOptional(String key);
}
