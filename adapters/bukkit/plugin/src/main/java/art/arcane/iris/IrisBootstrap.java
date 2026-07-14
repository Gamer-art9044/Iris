package art.arcane.iris;

import art.arcane.iris.core.pack.DefaultPackBootstrapProvisioner;
import art.arcane.iris.core.pack.DefaultPackBootstrapProvisioner.ProvisionResult;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;

import java.io.IOException;
import java.nio.file.Path;

@SuppressWarnings("UnstableApiUsage")
public final class IrisBootstrap implements PluginBootstrap {
    @Override
    public void bootstrap(BootstrapContext context) {
        ProvisionResult provisioned = provision(context);
        Path datapackRoot = provisioned.datapackRoot();
        context.getLogger().info("Iris startup datapack is {} at {}", provisioned.status(), datapackRoot);
    }

    private static ProvisionResult provision(BootstrapContext context) {
        try {
            return DefaultPackBootstrapProvisioner.provision(
                    context.getDataDirectory(),
                    message -> context.getLogger().info(message)
            );
        } catch (IOException e) {
            throw new IllegalStateException("Unable to provision the Iris startup datapack", e);
        }
    }
}
