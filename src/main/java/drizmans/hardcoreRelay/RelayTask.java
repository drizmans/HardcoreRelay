package drizmans.hardcoreRelay;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.UUID;

public class RelayTask extends BukkitRunnable {

    private final HardcoreRelay plugin;
    private int timeLeft;

    public RelayTask(HardcoreRelay plugin) {
        this.plugin = plugin;
        this.timeLeft = plugin.getConfig().getInt("turn-duration", 600);
    }

    @Override
    public void run() {
        if (plugin.getPlayerQueue().size() <= 1 && plugin.getConfig().getBoolean("wait-for-more-players")) {
            plugin.stopRelay(); // Pause if not enough players
            return;
        }

        Player currentPlayer = plugin.getCurrentPlayer();
        if (currentPlayer == null || !currentPlayer.isOnline()) {
            // Active player is gone, force a rotation
            plugin.rotatePlayer(false);
            return;
        }

        timeLeft--;

        if (timeLeft <= 0) {
            plugin.rotatePlayer(true); // Successful turn completion
            return; // New task will handle the next second
        }

        sendActionBarMessages(currentPlayer);

        // We keep teleporting the spectators to the active player because some environmental sounds (Eg. being under-water)
        // depend on where your player is, not the player you're spectating.
        // This still doesn't work perfectly if a rotation happens while under-water, but it prevents spectators being stuck with
        // annoying environmental noises until the next rotation.
        for (UUID spectatorId : plugin.getPlayerQueue()) {
            if (spectatorId.equals(currentPlayer.getUniqueId())) {
                continue;
            }
            Player spectator = plugin.getServer().getPlayer(spectatorId);
            // We only need to teleport if they are actually in spectator mode.
            if (spectator != null && spectator.isOnline() && spectator.getGameMode() == GameMode.SPECTATOR) {
                // Using SPECTATE cause can help some client-side predictions
                spectator.teleport(currentPlayer.getLocation(), PlayerTeleportEvent.TeleportCause.SPECTATE);
            }
        }
    }

    private void sendActionBarMessages(Player currentPlayer) {
        // Prepare messages from config
        String spectatorMsgFormat = ChatColor.translateAlternateColorCodes('&', plugin.getConfig().getString("spectator-message"));
        String activePlayerMsgFormat = ChatColor.translateAlternateColorCodes('&', plugin.getConfig().getString("active-player-countdown-message"));

        // Format shared stats
        String health = String.format("%.1f", currentPlayer.getHealth());
        String hunger = String.valueOf(currentPlayer.getFoodLevel());
        String time = formatTime(timeLeft);
        Player nextPlayer = plugin.getNextPlayer();
        String nextPlayerName = (nextPlayer != null) ? nextPlayer.getName() : "N/A";

        // Create the message for spectators
        String spectatorMessage = spectatorMsgFormat
                .replace("{health}", health)
                .replace("{hunger}", hunger)
                .replace("{time}", time)
                .replace("{next_player}", nextPlayerName);
        TextComponent spectatorComponent = new TextComponent(spectatorMessage);

        // Send messages
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.equals(currentPlayer)) {
                // Active player only gets a message in the last 10 seconds
                if (timeLeft <= 10) {
                    String activeMessage = activePlayerMsgFormat.replace("{time}", String.valueOf(timeLeft)).replace("{next_player}", nextPlayerName);;
                    p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(activeMessage));
                }
            } else if (p.getGameMode() == GameMode.SPECTATOR) {
                // Spectators get the full status update
                p.spigot().sendMessage(ChatMessageType.ACTION_BAR, spectatorComponent);
            }
        }
    }

    /**
     * Resets the timer to the full turn duration from the config.
     */
    public void resetTime() {
        this.timeLeft = plugin.getConfig().getInt("turn-duration", 600);
    }

    /**
     * Formats seconds into a MM:SS string.
     */
    private String formatTime(int seconds) {
        int minutes = seconds / 60;
        int remainingSeconds = seconds % 60;
        return String.format("%02d:%02d", minutes, remainingSeconds);
    }
}