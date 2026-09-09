package cn.jason31416.chatx.handler;

import cn.jason31416.chatx.ChatX;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class InteractiveChatBridge {
    private static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("interchat:main");
    private static final int MESSAGE_PROCESS_PACKET = 0x08;
    private static final int PLACEHOLDER_LIST_PACKET = 0x0C;
    private static final int FRAME_HEADER_SIZE = 14;
    private static final int MAX_CHUNK_SIZE = 32700;
    private static final Component ALREADY_PROCESSED = Component.text("<QUxSRUFEWVBST0NFU1NFRA==>");

    private final Map<FrameKey, byte[][]> incoming = new ConcurrentHashMap<>();
    private final Map<UUID, Consumer<Component>> pending = new ConcurrentHashMap<>();
    private final Map<String, List<Pattern>> placeholderPatterns = new ConcurrentHashMap<>();

    public static boolean isAvailable() {
        return ChatX.getProxy().getPluginManager().isLoaded("interactivechatvelocity");
    }

    public boolean containsPlaceholder(@Nonnull String message) {
        return placeholderPatterns.values().stream()
                .flatMap(List::stream)
                .anyMatch(pattern -> pattern.matcher(message).find());
    }

    public void process(@Nonnull Player sender, @Nonnull Player receiver, @Nonnull Component component,
                        @Nonnull Consumer<Component> callback) {
        if (!isAvailable() || receiver.getCurrentServer().isEmpty()) {
            callback.accept(component);
            return;
        }

        UUID messageId = UUID.randomUUID();
        pending.put(messageId, callback);
        try {
            Component tagged = InteractiveChatHook.tagSender(sender, component);
            sendRequest(receiver, receiver.getCurrentServer().orElseThrow().getServer(), messageId, tagged);
        } catch (Exception exception) {
            pending.remove(messageId);
            ChatX.getLogger().warn(
                    "Unable to send InteractiveChat processing request for {} on {}",
                    receiver.getUsername(),
                    receiver.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("unknown"),
                    exception
            );
            callback.accept(component);
            return;
        }

        ChatX.getProxy().getScheduler().buildTask(ChatX.getInstance(), () -> {
            Consumer<Component> expired = pending.remove(messageId);
            if (expired != null) {
                ChatX.getLogger().warn(
                        "InteractiveChat processing timed out for {} on {}",
                        receiver.getUsername(),
                        receiver.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("unknown")
                );
                expired.accept(component);
            }
        }).delay(3, TimeUnit.SECONDS).schedule();
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onPluginMessage(@Nonnull PluginMessageEvent event) {
        if (!event.getIdentifier().getId().equals(CHANNEL.getId())
                || !(event.getSource() instanceof ServerConnection connection)
                || event.getData().length < FRAME_HEADER_SIZE) {
            return;
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(event.getData()));
            int packetNumber = input.readInt();
            int chunkIndex = input.readInt();
            int chunkCount = input.readInt();
            int packetId = input.readUnsignedShort();
            if ((packetId != MESSAGE_PROCESS_PACKET && packetId != PLACEHOLDER_LIST_PACKET)
                    || chunkCount <= 0 || chunkIndex < 0 || chunkIndex >= chunkCount) {
                return;
            }

            byte[] chunk = input.readAllBytes();
            String serverName = connection.getServerInfo().getName();
            FrameKey frameKey = new FrameKey(serverName, packetNumber, packetId);
            byte[][] chunks = incoming.compute(frameKey, (key, current) -> {
                byte[][] result = current != null && current.length == chunkCount ? current : new byte[chunkCount][];
                result[chunkIndex] = chunk;
                return result;
            });
            if (Arrays.stream(chunks).anyMatch(value -> value == null)) {
                return;
            }
            incoming.remove(frameKey);

            ByteArrayOutputStream joined = new ByteArrayOutputStream();
            for (byte[] part : chunks) {
                joined.write(part);
            }
            if (packetId == MESSAGE_PROCESS_PACKET) {
                readResponse(joined.toByteArray());
            } else {
                readPlaceholderList(serverName, joined.toByteArray());
            }
        } catch (Exception exception) {
            ChatX.getLogger().warn("Failed to read InteractiveChat response", exception);
        }
    }

    private void readResponse(byte[] data) throws Exception {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(data));
        UUID messageId = readUuid(input);
        Consumer<Component> callback = pending.remove(messageId);
        if (callback == null) {
            return;
        }
        Component processed = GsonComponentSerializer.gson().deserialize(readString(input));
        ChatX.getProxy().getScheduler().buildTask(ChatX.getInstance(), () -> callback.accept(processed)).schedule();
    }

    private void readPlaceholderList(String serverName, byte[] data) throws Exception {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(data));
        int size = input.readInt();
        if (size < 0 || size > 10000) {
            throw new IllegalArgumentException("Invalid InteractiveChat placeholder count: " + size);
        }

        List<Pattern> patterns = new java.util.ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            boolean builtIn = input.readBoolean();
            String keyword;
            if (builtIn) {
                keyword = readString(input);
                readString(input); // name
                readString(input); // description component
                readString(input); // permission
                input.readLong(); // cooldown
            } else {
                readString(input); // key
                input.readByte(); // parse player
                keyword = readString(input);
                input.readBoolean(); // parse keyword
                input.readLong(); // cooldown
                input.readBoolean(); // hover enabled
                readString(input); // hover component
                input.readBoolean(); // click enabled
                readString(input); // click action
                readString(input); // click value
                input.readBoolean(); // replace enabled
                readString(input); // replacement component
                readString(input); // name
                readString(input); // description component
            }
            patterns.add(Pattern.compile(keyword));
        }
        placeholderPatterns.put(serverName, List.copyOf(patterns));
    }

    private void sendRequest(Player receiver, RegisteredServer server, UUID messageId, Component component) throws Exception {
        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
        try (DataOutputStream payload = new DataOutputStream(payloadBytes)) {
            writeUuid(payload, messageId);
            writeUuid(payload, receiver.getUniqueId());
            writeString(payload, GsonComponentSerializer.gson().serialize(component));
            payload.writeBoolean(false);
        }

        byte[] data = payloadBytes.toByteArray();
        int chunkCount = Math.max(1, (data.length + MAX_CHUNK_SIZE - 1) / MAX_CHUNK_SIZE);
        int packetNumber = ThreadLocalRandom.current().nextInt();
        for (int index = 0; index < chunkCount; index++) {
            int from = index * MAX_CHUNK_SIZE;
            int to = Math.min(data.length, from + MAX_CHUNK_SIZE);
            ByteArrayOutputStream frameBytes = new ByteArrayOutputStream();
            try (DataOutputStream frame = new DataOutputStream(frameBytes)) {
                frame.writeInt(packetNumber);
                frame.writeInt(index);
                frame.writeInt(chunkCount);
                frame.writeShort(MESSAGE_PROCESS_PACKET);
                frame.write(data, from, to - from);
            }
            if (!server.sendPluginMessage(CHANNEL, frameBytes.toByteArray())) {
                throw new IllegalStateException("InteractiveChat backend channel is unavailable");
            }
        }
    }

    public static Component markProcessed(Component component) {
        return component.append(ALREADY_PROCESSED);
    }

    private static UUID readUuid(DataInputStream input) throws Exception {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeUuid(DataOutputStream output, UUID uuid) throws Exception {
        output.writeLong(uuid.getMostSignificantBits());
        output.writeLong(uuid.getLeastSignificantBits());
    }

    private static String readString(DataInputStream input) throws Exception {
        int length = input.readInt();
        if (length < 0 || length > input.available()) {
            throw new IllegalArgumentException("Invalid InteractiveChat string length: " + length);
        }
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static void writeString(DataOutputStream output, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private record FrameKey(String server, int packetNumber, int packetId) {
    }
}
