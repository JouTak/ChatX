package cn.jason31416.chatx.handler;

import cn.jason31416.chatx.ChatX;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;

import javax.annotation.Nonnull;

public final class InteractiveChatHook {
    private static final String PLUGIN_ID = "interactivechatvelocity";

    private InteractiveChatHook() {
    }

    public static boolean containsPlaceholder(@Nonnull String message) {
        return isAvailable()
                && ChatX.getInteractiveChatBridge() != null
                && ChatX.getInteractiveChatBridge().containsPlaceholder(message);
    }

    public static @Nonnull Component tagSender(@Nonnull Player player, @Nonnull Component component) {
        if (!isAvailable()) {
            return component;
        }
        return Component.text("<chat=" + player.getUniqueId() + ">").append(component);
    }

    private static boolean isAvailable() {
        return ChatX.getProxy().getPluginManager().isLoaded(PLUGIN_ID);
    }
}
