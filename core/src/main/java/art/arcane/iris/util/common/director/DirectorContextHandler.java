package art.arcane.iris.util.common.director;

import art.arcane.volmlib.util.director.context.DirectorContextHandlers;
import art.arcane.volmlib.util.director.context.DirectorContextHandlerType;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.util.common.plugin.VolmitSender;

import java.util.Map;

public interface DirectorContextHandler<T> extends DirectorContextHandlerType<T, VolmitSender> {
    Map<Class<?>, DirectorContextHandler<?>> contextHandlers = buildContextHandlers();

    static Map<Class<?>, DirectorContextHandler<?>> buildContextHandlers() {
        return DirectorContextHandlers.buildOrEmpty(
                DirectorSystem.initializePackage("art.arcane.iris.util.common.director.context"),
                DirectorContextHandler.class,
                h -> ((DirectorContextHandler<?>) h).getType(),
                e -> {
                    IrisLogging.reportError(e);
                    e.printStackTrace();
                });
    }
}
