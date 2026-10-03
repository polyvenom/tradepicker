package net.fabricmc.fabric.api.gametest.v1;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Stands in for Fabric's annotation so the shared test classes compile on NeoForge. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GameTest {
    int maxTicks() default 20;
}
