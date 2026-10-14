package cn.jason31416.chatx.discord;

import cn.jason31416.chatx.ChatX;

import javax.annotation.Nonnull;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class DiscordTabImageService {
    private final DiscordManager manager;
    private final ThreadPoolExecutor renders;
    private DiscordTabImageRenderer renderer;

    DiscordTabImageService(@Nonnull DiscordManager manager) {
        this.manager = manager;
        int capacity = manager.getConfig().getPlayerListCommand().image().renderQueueCapacity();
        this.renders = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), task -> {
                    Thread thread = new Thread(task, "chatx-discord-tab-render");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    void submit(@Nonnull DiscordRoute route, @Nonnull String destinationId) {
        DiscordConfig.PlayerListCommand command = manager.getConfig().getPlayerListCommand();
        DiscordConfig.PlayerListImage config = command.image();
        AtomicBoolean responded = new AtomicBoolean();
        AtomicReference<DiscordTabSnapshot> captured = new AtomicReference<>();
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(config.renderTimeoutSeconds());
        Future<?> job;
        try{
            job = renders.submit(() -> {
                DiscordTabSnapshot snapshot = null;
                try{
                    snapshot = DiscordTabSnapshot.capture(route, config);
                    captured.set(snapshot);
                    String response = fallback(snapshot, command, route);
                    if(renderer == null) renderer = new DiscordTabImageRenderer();
                    byte[] image = renderer.render(snapshot, config, deadline);
                    if(System.currentTimeMillis() >= deadline) throw new IllegalStateException("TAB render timed out");
                    if(responded.compareAndSet(false, true)){
                        if(!manager.publishCommandImageResponse(destinationId, image, response,
                                command.deleteAfterSeconds())){
                            manager.publishCommandResponse(destinationId, response, command.deleteAfterSeconds());
                        }
                    }
                }catch (Exception | LinkageError error){
                    if(responded.compareAndSet(false, true)){
                        sendFallback(route, destinationId, command, snapshot);
                    }
                }
            });
        }catch (RejectedExecutionException error){
            sendFallback(route, destinationId, command, null);
            return;
        }
        try{
            ScheduledFuture<?> timeout = manager.getJda().getRateLimitPool().schedule(() -> {
                if(responded.compareAndSet(false, true)){
                    job.cancel(true);
                    sendFallback(route, destinationId, command, captured.get());
                }
            }, config.renderTimeoutSeconds(), TimeUnit.SECONDS);
            if(job.isDone()) timeout.cancel(false);
        }catch (RejectedExecutionException error){
            job.cancel(true);
            if(responded.compareAndSet(false, true)){
                sendFallback(route, destinationId, command, captured.get());
            }
        }
    }

    void shutdown() {
        renders.shutdownNow();
        renders.getQueue().clear();
    }

    private void sendFallback(DiscordRoute route, String destinationId,
                              DiscordConfig.PlayerListCommand command, DiscordTabSnapshot snapshot) {
        ChatX.getProxy().getScheduler().buildTask(ChatX.getInstance(), () ->
                manager.publishCommandResponse(destinationId, fallback(snapshot, command, route),
                        command.deleteAfterSeconds())
        ).schedule();
    }

    private static String fallback(DiscordTabSnapshot snapshot, DiscordConfig.PlayerListCommand command,
                                   DiscordRoute route) {
        String original = DiscordPlayerListCommand.render(
                DiscordPlayerListCommand.players(route), command.formats());
        if(command.image().textFallback().isBlank()) return original;
        if(snapshot == null){
            try{
                snapshot = DiscordTabSnapshot.capture(route, command.image());
            }catch (RuntimeException | LinkageError ignored){
                return original;
            }
        }
        String custom = DiscordTabSnapshot.apply(command.image().textFallback(), snapshot.placeholders());
        return custom.isBlank() || custom.length() > 2_000 ? original : custom;
    }
}
