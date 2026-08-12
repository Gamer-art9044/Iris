package art.arcane.iris;

import art.arcane.iris.core.lifecycle.BukkitStartupPaths;
import art.arcane.iris.core.lifecycle.WorldReplacementBootstrap;
import art.arcane.iris.core.lifecycle.WorldReplacementBootstrapMarker;
import art.arcane.iris.core.pack.DefaultPackBootstrapProvisioner;
import art.arcane.iris.core.pack.DefaultPackBootstrapProvisioner.ProvisionResult;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEventType;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import java.io.IOException;
import java.nio.file.Path;

@SuppressWarnings("UnstableApiUsage")
public final class IrisBootstrap implements PluginBootstrap {
    @Override
    public void bootstrap(BootstrapContext context) {
        WorldReplacementBootstrapMarker.markBootstrapped();
        try {
            BukkitStartupPaths startupPaths = BukkitStartupPaths.resolveCurrent();
            reconcilePendingWorldReplacements(context, startupPaths);
            ProvisionResult provisioned = provision(context, startupPaths);
            Path datapackRoot = provisioned.datapackRoot();
            context.getLogger().info("Iris startup datapack is {} at {}", provisioned.status(), datapackRoot);
        } catch (Throwable failure) {
            armStartupFailure(context, failure);
        }
    }

    private static void reconcilePendingWorldReplacements(
            BootstrapContext context,
            BukkitStartupPaths startupPaths
    ) {
        try {
            WorldReplacementBootstrap.ReconcileResult result = WorldReplacementBootstrap.reconcile(
                    context.getDataDirectory(),
                    startupPaths.levelRoot(),
                    startupPaths.bukkitConfiguration(),
                    message -> context.getLogger().info(message)
            );
            if (result.transactions() > 0) {
                context.getLogger().info(
                        "Reconciled {} pending Iris world replacement(s): {} published, {} rolled back, {} retained",
                        result.transactions(),
                        result.published(),
                        result.rolledBack(),
                        result.retained()
                );
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Unable to reconcile pending Iris world replacements before registry bootstrap",
                    failure
            );
        }
    }

    static void armStartupFailure(BootstrapContext context, Throwable failure) {
        armStartupFailure(context, failure, LifecycleEvents.DATAPACK_DISCOVERY);
    }

    static <E extends LifecycleEvent> void armStartupFailure(
            BootstrapContext context,
            Throwable failure,
            LifecycleEventType<? super BootstrapContext, ? extends E, ?> eventType
    ) {
        context.getLifecycleManager().registerEventHandler(eventType, event -> {
            throw new IllegalStateException("Iris bootstrap did not establish safe world-generation state.", failure);
        });
        try {
            context.getLogger().error(
                    "Iris bootstrap failed; registry and world startup will be stopped at datapack discovery.",
                    failure
            );
        } catch (Throwable loggingFailure) {
            failure.addSuppressed(loggingFailure);
            failure.printStackTrace(System.err);
        }
    }

    private static ProvisionResult provision(BootstrapContext context, BukkitStartupPaths startupPaths) {
        try {
            return DefaultPackBootstrapProvisioner.provision(
                    context.getDataDirectory(),
                    message -> context.getLogger().info(message),
                    startupPaths
            );
        } catch (IOException e) {
            throw new IllegalStateException("Unable to provision the Iris startup datapack", e);
        }
    }
}
