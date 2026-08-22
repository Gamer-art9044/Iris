package art.arcane.iris.client;

import art.arcane.iris.spi.protocol.IrisMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.network.chat.Component;

/**
 * CLIENT DIST ONLY. See {@link IrisClientHud} for why the dist marker is a javadoc contract plus a bytecode
 * test rather than an @Environment annotation.
 */
public final class IrisToastPresenter {
    private IrisToastPresenter() {
    }

    public static void pump() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gui == null) {
            return;
        }
        ToastManager manager = minecraft.gui.toastManager();
        IrisClientToasts toasts = IrisClient.toasts();
        IrisClientToasts.Pending next = toasts.poll();
        while (next != null) {
            SystemToast.addOrUpdate(manager, tokenFor(next.kind()), Component.literal(next.title()), Component.literal(next.body()));
            next = toasts.poll();
        }
    }

    private static SystemToast.SystemToastId tokenFor(int kind) {
        return switch (kind) {
            case IrisMessage.Toast.KIND_SUCCESS -> SystemToast.SystemToastId.WORLD_BACKUP;
            case IrisMessage.Toast.KIND_WARNING -> SystemToast.SystemToastId.UNSECURE_SERVER_WARNING;
            case IrisMessage.Toast.KIND_ERROR -> SystemToast.SystemToastId.PACK_LOAD_FAILURE;
            default -> SystemToast.SystemToastId.PERIODIC_NOTIFICATION;
        };
    }
}
