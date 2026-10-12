package cn.jason31416.chatx.discord;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class DiscordRouter {
    private final DiscordRoute globalRoute;
    private final Map<String, DiscordRoute> localRoutes;
    private final Map<String, DiscordRoute> destinationRoutes;

    public DiscordRouter(@Nonnull Collection<DiscordRoute> routes) {
        DiscordRoute globalRoute = null;
        Map<String, DiscordRoute> localRoutes = new HashMap<>();
        Map<String, DiscordRoute> destinationRoutes = new HashMap<>();

        for(DiscordRoute route : routes){
            if(route.type() == DiscordRoute.Type.GLOBAL && globalRoute == null){
                globalRoute = route;
            }else if(route.type() == DiscordRoute.Type.LOCAL){
                localRoutes.putIfAbsent(normalizeBackend(route.backend()), route);
            }
            destinationRoutes.putIfAbsent(route.effectiveDestinationId(), route);
        }

        this.globalRoute = globalRoute;
        this.localRoutes = Map.copyOf(localRoutes);
        this.destinationRoutes = Map.copyOf(destinationRoutes);
    }

    public Optional<DiscordRoute> routeMinecraft(@Nonnull DiscordEvent event) {
        if(event.type() == DiscordRoute.Type.GLOBAL) return routeGlobal();
        return routeLocal(event.backend());
    }

    public Optional<DiscordRoute> routeGlobal() {
        return Optional.ofNullable(globalRoute);
    }

    public Optional<DiscordRoute> routeLocal(@Nonnull String backend) {
        return Optional.ofNullable(localRoutes.get(normalizeBackend(backend)));
    }

    public Optional<DiscordRoute> routeDiscord(@Nonnull String destinationId) {
        return Optional.ofNullable(destinationRoutes.get(destinationId));
    }

    private static String normalizeBackend(String backend) {
        return backend.toLowerCase(Locale.ROOT);
    }
}
