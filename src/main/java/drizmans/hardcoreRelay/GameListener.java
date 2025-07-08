package drizmans.hardcoreRelay;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;

import java.util.UUID;

public class GameListener implements Listener {

    private final HardcoreRelay plugin;

    public GameListener(HardcoreRelay plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // If game is over, new players are just spectators
        if (plugin.isGameOver()) {
            player.setGameMode(GameMode.SPECTATOR);
            player.sendMessage(org.bukkit.ChatColor.RED + "The Hardcore Relay has already ended. You are a spectator.");
            return;
        }

        // Add player to the queue if they aren't already in it
        if (!plugin.getPlayerQueue().contains(playerUUID)) {
            plugin.getPlayerQueue().add(playerUUID);
        }

        player.sendMessage(org.bukkit.ChatColor.GOLD + "You have joined the Hardcore Relay!");

        // Just tell the main plugin to check the state. It will handle the rest.
        plugin.checkAndStartRelay();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Schedule the quit logic to run on the next server tick.
        // This ensures the player is fully removed from the online player list
        // before we check how many players are left.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            plugin.handlePlayerQuit(event.getPlayer());
        });
    }

    /**
     * Intercepts damage to the active player to prevent death.
     * If damage would be fatal, it cancels the event, heals the character,
     * and triggers the death/rotation logic.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getEntity();

        // Only act if the damaged player is the current active player
        if (player.getUniqueId().equals(plugin.getCurrentPlayerUUID())) {
            // Check if the damage would be fatal
            if (player.getHealth() - event.getFinalDamage() <= 0) {
                // Prevent the death!
                event.setCancelled(true);
                plugin.getLogger().info("[DEBUG] Fatal damage prevented for " + player.getName() + ". Triggering relay death logic.");

                // Heal the "character" to full health for the next person
                player.setHealth(20.0);
                //player.setFoodLevel(20);

                // Now, trigger the same logic as if a death occurred
                plugin.handlePlayerDeath();
            }
        }
    }

    @EventHandler
    public void onSpectatorInteract(PlayerInteractEvent event) {
        // Prevent spectators from interacting with anything
        if (event.getPlayer().getGameMode() == GameMode.SPECTATOR) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        if (plugin.isGameOver()) {
            return;
        }

        Player player = event.getPlayer();
        Player activePlayer = plugin.getCurrentPlayer();

        // If the player is a spectator and is not the active player
        if (player.getGameMode() == GameMode.SPECTATOR && activePlayer != null && !player.equals(activePlayer)) {
            // Re-apply the spectator target on the next server tick to override the dismount
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if(player.getGameMode() == GameMode.SPECTATOR) { // Re-check in case their mode changed
                    player.setSpectatorTarget(null);
                    player.setSpectatorTarget(activePlayer);
                }
            });
        }
    }

    /**
     * Handles re-locking spectator view when the active player changes dimensions.
     */
    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player changedPlayer = event.getPlayer();
        Player activePlayer = plugin.getCurrentPlayer();

        // Check if the player who changed worlds is the currently active player
        if (activePlayer != null && changedPlayer.getUniqueId().equals(activePlayer.getUniqueId())) {
            plugin.getLogger().info("[DEBUG] Active player " + activePlayer.getName() + " changed worlds. Re-applying spectator lock for all other players.");

            // Schedule a task to re-apply the spectator lock after a short delay
            // This gives clients time to load the new world for the active player
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                for (UUID spectatorId : plugin.getPlayerQueue()) {
                    if (spectatorId.equals(activePlayer.getUniqueId())) {
                        continue; // Skip the active player
                    }
                    Player spectator = plugin.getServer().getPlayer(spectatorId);
                    if (spectator != null) {
                        plugin.getLogger().info("[DEBUG] Re-locking " + spectator.getName() + " to " + activePlayer.getName());
                        plugin.forceSpectate(spectator, activePlayer);
                    }
                }
            }, 20L); // 20 ticks = 1 second delay
        }
    }


}