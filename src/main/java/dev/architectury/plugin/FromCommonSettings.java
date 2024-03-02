package dev.architectury.plugin;

import dev.architectury.plugin.utils.ClosureAction;
import groovy.lang.Closure;
import groovy.lang.DelegatesTo;
import org.gradle.api.Action;
import org.gradle.api.file.CopySpec;
import org.gradle.api.provider.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Settings to configure {@link ArchitectPluginExtension#fromCommon(Object) architectury.fromCommon}.
 */
public interface FromCommonSettings {
    /**
     * The configuration for bundling common projects.
     */
    Property<BundleSettings> getBundle();

    /**
     * Configures the bundle settings.
     *
     * @param action the action that configures them
     */
    default void bundle(Action<BundleSettings> action) {
        Objects.requireNonNull(action, "Action cannot be null");
        action.execute(getBundle().get());
    }

    /**
     * Configures the bundle settings.
     *
     * @param action the closure that configures them
     */
    default void bundle(@DelegatesTo(BundleSettings.class) Closure<?> action) {
        Objects.requireNonNull(action, "Closure cannot be null");
        bundle(new ClosureAction<>(action));
    }

    /**
     * Settings to configure how common files are bundled.
     */
    abstract class BundleSettings {
        final List<Action<CopySpec>> configActions = new ArrayList<>(0);

        /**
         * Controls whether the common jar's contents are added to this project's
         * {@code jar} task. True by default.
         */
        public abstract Property<Boolean> getEnabled();

        /**
         * Configures the {@link CopySpec} that bundles the common files.
         *
         * @param action the action that configures it
         */
        public void configure(Action<CopySpec> action) {
            Objects.requireNonNull(action, "Action cannot be null");
            configActions.add(action);
        }

        /**
         * Configures the {@link CopySpec} that bundles the common files.
         *
         * @param action the closure that configures it
         */
        public void configure(@DelegatesTo(CopySpec.class) Closure<?> action) {
            Objects.requireNonNull(action, "Closure cannot be null");
            configure(new ClosureAction<>(action));
        }
    }
}
