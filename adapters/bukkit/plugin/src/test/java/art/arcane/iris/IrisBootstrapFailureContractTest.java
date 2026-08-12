package art.arcane.iris;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEvent;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.handler.LifecycleEventHandler;
import io.papermc.paper.plugin.lifecycle.event.handler.configuration.LifecycleEventHandlerConfiguration;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEventType;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.junit.Test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IrisBootstrapFailureContractTest {
    @Test
    public void bootstrapFailureAbortsDatapackDiscoveryInsteadOfDisablingOnlyIris() {
        BootstrapContext context = mock(BootstrapContext.class);
        ComponentLogger logger = mock(ComponentLogger.class);
        CapturingLifecycleManager manager = new CapturingLifecycleManager();
        IllegalStateException cause = new IllegalStateException("unsafe transaction");
        LifecycleEventType<BootstrapContext, LifecycleEvent, ?> eventType = lifecycleEventType();
        when(context.getLogger()).thenReturn(logger);
        when(context.getLifecycleManager()).thenReturn(manager);

        IrisBootstrap.armStartupFailure(context, cause, eventType);

        assertSame(eventType, manager.eventType);
        IllegalStateException failure = assertThrows(IllegalStateException.class, manager::runCapturedHandler);
        assertSame(cause, failure.getCause());
    }

    @SuppressWarnings("unchecked")
    private static LifecycleEventType<BootstrapContext, LifecycleEvent, ?> lifecycleEventType() {
        return (LifecycleEventType<BootstrapContext, LifecycleEvent, ?>) mock(LifecycleEventType.class);
    }

    private static final class CapturingLifecycleManager implements LifecycleEventManager<BootstrapContext> {
        private LifecycleEventType<?, ?, ?> eventType;
        private LifecycleEventHandler<?> handler;

        @Override
        public <E extends LifecycleEvent> void registerEventHandler(
                LifecycleEventType<? super BootstrapContext, ? extends E, ?> eventType,
                LifecycleEventHandler<? super E> eventHandler
        ) {
            this.eventType = eventType;
            this.handler = eventHandler;
        }

        @Override
        public void registerEventHandler(
                LifecycleEventHandlerConfiguration<? super BootstrapContext> handlerConfiguration
        ) {
            throw new AssertionError("The direct handler overload must be used.");
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private void runCapturedHandler() {
            ((LifecycleEventHandler) handler).run(null);
        }
    }
}
