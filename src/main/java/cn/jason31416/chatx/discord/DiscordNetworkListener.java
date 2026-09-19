package cn.jason31416.chatx.discord;

import cn.jason31416.chatx.ChatX;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import javax.annotation.Nonnull;

public class DiscordNetworkListener {
    @Subscribe
    public void onPostLogin(@Nonnull PostLoginEvent event) {
        DiscordManager manager = ChatX.getDiscordManager();
        if(manager == null) return;
        manager.publishNetworkJoin(event.getPlayer());
        manager.updatePresence(onlineAfterJoin(event.getPlayer()));
    }

    @Subscribe
    public void onServerPostConnect(@Nonnull ServerPostConnectEvent event) {
        DiscordManager manager = ChatX.getDiscordManager();
        if(manager == null || event.getPlayer().getCurrentServer().isEmpty()) return;

        String backend = event.getPlayer().getCurrentServer().orElseThrow().getServerInfo().getName();
        RegisteredServer previousServer = event.getPreviousServer();
        if(previousServer == null){
            manager.publishBackendJoin(event.getPlayer(), backend);
            return;
        }

        String previousBackend = previousServer.getServerInfo().getName();
        manager.publishServerSwitch(event.getPlayer(), previousBackend, backend);
    }

    @Subscribe
    public void onDisconnect(@Nonnull DisconnectEvent event) {
        DiscordManager manager = ChatX.getDiscordManager();
        if(manager == null) return;

        String backend = event.getPlayer().getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null);
        manager.publishNetworkLeave(event.getPlayer(), backend);
        manager.updatePresence(onlineAfterLeave(event.getPlayer()));
    }

    private static int onlineAfterJoin(@Nonnull Player player) {
        return (int) ChatX.getProxy().getAllPlayers().stream()
                .filter(online -> !online.getUniqueId().equals(player.getUniqueId()))
                .count() + 1;
    }

    private static int onlineAfterLeave(@Nonnull Player player) {
        return (int) ChatX.getProxy().getAllPlayers().stream()
                .filter(online -> !online.getUniqueId().equals(player.getUniqueId()))
                .count();
    }
}
