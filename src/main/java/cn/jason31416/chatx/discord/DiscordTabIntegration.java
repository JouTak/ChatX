package cn.jason31416.chatx.discord;

import com.velocitypowered.api.proxy.Player;
import io.github.miniplaceholders.api.MiniPlaceholders;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.tablist.SortingManager;
import me.neznamy.tab.api.tablist.TabListFormatManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import javax.annotation.Nonnull;
import java.util.UUID;

/** Only loaded when the optional TAB plugin is present. */
final class DiscordTabIntegration {
    private DiscordTabIntegration() {}

    @Nonnull
    static DiscordTabValues values(@Nonnull Player player) {
        TabAPI api = TabAPI.getInstance();
        UUID uuid = player.getUniqueId();
        TabPlayer tabPlayer = api.getPlayer(uuid);
        if(tabPlayer == null || !tabPlayer.isLoaded()) return DiscordTabValues.EMPTY;

        TabListFormatManager format = api.getTabListFormatManager();
        SortingManager sorting = api.getSortingManager();
        String prefix = "";
        String name = player.getUsername();
        String suffix = "";
        String originalPrefix = "";
        String originalName = name;
        String originalSuffix = "";
        if(format != null){
            originalPrefix = value(format.getOriginalReplacedPrefix(tabPlayer));
            originalName = value(format.getOriginalReplacedName(tabPlayer));
            if(originalName.isBlank()) originalName = name;
            originalSuffix = value(format.getOriginalReplacedSuffix(tabPlayer));
            prefix = applied(format.getCustomPrefix(tabPlayer), originalPrefix);
            name = applied(format.getCustomName(tabPlayer), originalName);
            suffix = applied(format.getCustomSuffix(tabPlayer), originalSuffix);
            try{
                TagResolver resolver = MiniPlaceholders.audienceGlobalPlaceholders();
                String resolvedPrefix = resolved(player, resolver, "<tab_tabprefix>");
                String resolvedName = resolved(player, resolver, "<tab_customtabname>");
                String resolvedSuffix = resolved(player, resolver, "<tab_tabsuffix>");
                if(!resolvedName.isBlank() && !resolvedName.contains("<tab_")){
                    prefix = resolvedPrefix;
                    name = resolvedName;
                    suffix = resolvedSuffix;
                }
            }catch (RuntimeException | LinkageError ignored){
                // MiniPlaceholders is optional. Keep the TAB API values as fallback.
            }
        }
        String sortKey = "";
        if(sorting != null){
            sortKey = value(applied(sorting.getForcedTeamName(tabPlayer), sorting.getOriginalTeamName(tabPlayer)));
        }
        return new DiscordTabValues(prefix, name, suffix, value(tabPlayer.getGroup()), sortKey, value(tabPlayer.getWorld()),
                originalPrefix, originalName, originalSuffix);
    }

    private static String applied(String custom, String original) {
        return custom == null ? original : custom;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String resolved(Player player, TagResolver resolver, String placeholder) {
        return LegacyComponentSerializer.legacySection().serialize(
                MiniMessage.miniMessage().deserialize(placeholder, player, resolver));
    }

}
