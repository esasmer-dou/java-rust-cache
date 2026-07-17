package com.reactor.rust.cache.projection;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates materializer registration from a shared projection enum.
 *
 * <p>For enum constant {@code CUSTOMER_DETAIL}, the owner must expose a package-visible
 * method named {@code writeCustomerDetail(ProjectionTarget)}.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface GenerateProjectionRegistry {
    Class<? extends Enum<?>> value();
}
