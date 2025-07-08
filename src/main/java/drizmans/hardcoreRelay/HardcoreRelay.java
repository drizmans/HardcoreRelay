package drizmans.hardcoreRelay;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.bukkit.potion.PotionEffectType;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

public final class HardcoreRelay extends JavaPlugin {

    // --- Game State Variables ---
    private final List<UUID> playerQueue = new ArrayList<>();
    private final Map<UUID, Integer> deathSkips = new HashMap<>();
    private int currentPlayerIndex = -1;
    private int consecutiveDeaths = 0;
    private RelayTask relayTask;
    private BukkitTask inventorySyncTask;
    private BukkitTask freezeTask;


    // --- PDC Keys ---
    private NamespacedKey gameOverKey;
    private NamespacedKey healthKey, foodKey, saturationKey, experienceKey, locationKey, inventoryKey, enderChestKey, effectsKey;

    // --- In-Memory State ---
    private Player lastActivePlayer = null;
    private SharedState loadedState = null; // Holds state loaded from PDC on startup

    //================================================================================
    // Plugin Lifecycle (onEnable / onDisable)
    //================================================================================

    @Override
    public void onEnable() {
        // Initialize all PDC keys
        initializeKeys();
        saveDefaultConfig();

        // Load the persistent state from the world's PDC
        loadStateFromPDC();

        if (isGameOver()) {
            getLogger().info("[DEBUG] Hardcore Relay is in a 'Game Over' state.");
        }

        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        checkAndStartRelay();
        getLogger().info("HardcoreRelay has been enabled!");
    }

    @Override
    public void onDisable() {
        // Use the new authoritative save method
        updateAndSaveState();
        stopRelay();
        getLogger().info("HardcoreRelay has been disabled!");
    }

    private void initializeKeys() {
        gameOverKey = new NamespacedKey(this, "is_game_over");
        healthKey = new NamespacedKey(this, "relay_health");
        foodKey = new NamespacedKey(this, "relay_food");
        saturationKey = new NamespacedKey(this, "relay_saturation");
        experienceKey = new NamespacedKey(this, "relay_experience");
        locationKey = new NamespacedKey(this, "relay_location");
        inventoryKey = new NamespacedKey(this, "relay_inventory");
        enderChestKey = new NamespacedKey(this, "relay_enderchest");
        effectsKey = new NamespacedKey(this, "relay_effects");
    }


    //================================================================================
    // Core Game Logic
    //================================================================================

    public void checkAndStartRelay() {
        // Cancel any existing freeze task when we check the state.
        if (freezeTask != null) {
            freezeTask.cancel();
            freezeTask = null;
            // The player might still have effects, so let's clear them
            if (!playerQueue.isEmpty()) {
                Player firstPlayer = getServer().getPlayer(playerQueue.get(0));
                clearFreezeEffects(firstPlayer);
            }
        }

        if (isGameOver() || (relayTask != null && !relayTask.isCancelled())) {
            return;
        }
        playerQueue.clear();
        getServer().getOnlinePlayers().stream().map(Player::getUniqueId).forEach(playerQueue::add);

        boolean waitForPlayers = getConfig().getBoolean("wait-for-more-players", true);
        if (playerQueue.size() > 1 || (!waitForPlayers && !playerQueue.isEmpty())) {
            getServer().getScheduler().runTaskLater(this, this::startRelay, 1L);
        } else if (!playerQueue.isEmpty()) {
            // Only one player online. Freeze them.
            Player p = getServer().getPlayer(playerQueue.get(0));
            if (p != null) {
                freezePlayer(p);
            }
        }
    }

    public void startRelay() {
        if (isGameOver() || playerQueue.isEmpty()) return;

        loadStateFromPDC();
        getLogger().info("[DEBUG] Firing startRelay(), attempting to reload state from PDC first.");

        if (relayTask != null && !relayTask.isCancelled()) {
            relayTask.cancel();
        }
        currentPlayerIndex = 0;
        consecutiveDeaths = 0;
        this.relayTask = new RelayTask(this);
        this.relayTask.runTaskTimer(this, 0L, 20L);
        startInventorySyncTask(); // Start the real-time inventory sync
        applyPlayerState();
        getLogger().info("Hardcore Relay started!");
    }

    public void stopRelay() {
        if (this.relayTask != null) {
            this.relayTask.cancel();
            this.relayTask = null;
        }
        if (this.inventorySyncTask != null) {
            this.inventorySyncTask.cancel();
            this.inventorySyncTask = null;
        }
    }

    public void rotatePlayer(boolean isSuccessfulTurn) {
        if (playerQueue.isEmpty()) {
            stopRelay();
            return;
        }

        if (this.isGameOver()) {
            stopRelay();
            return;
        }

        if (isSuccessfulTurn) {
            consecutiveDeaths = 0;
            getLogger().info("Successful turn! Death counter reset.");
        }
        deathSkips.replaceAll((uuid, skips) -> skips - 1);
        deathSkips.entrySet().removeIf(entry -> entry.getValue() <= 0);

        Player outgoingPlayer = getCurrentPlayer();
        if (outgoingPlayer != null) {
            outgoingPlayer.closeInventory();
            getLogger().info("[DEBUG] Closing inventory for outgoing player: " + outgoingPlayer.getName());
        }

        updateAndSaveState();

        int attempts = 0;
        do {
            currentPlayerIndex = (currentPlayerIndex + 1) % playerQueue.size();
            attempts++;
        } while ((deathSkips.containsKey(getCurrentPlayerUUID()) || getServer().getPlayer(getCurrentPlayerUUID()) == null) && attempts <= playerQueue.size());

        if (attempts > playerQueue.size() || getServer().getPlayer(getCurrentPlayerUUID()) == null) {
            getLogger().warning("No valid players to rotate to. Pausing.");
            stopRelay();
            return;
        }
        applyPlayerState();
        if (relayTask != null) {
            relayTask.resetTime();
        }
        Player newPlayer = getCurrentPlayer();
        if (newPlayer != null) {
            getServer().broadcastMessage(ChatColor.GOLD + "It's now " + newPlayer.getName() + "'s turn!");
        }
    }

    public void handlePlayerDeath() {
        this.consecutiveDeaths++;
        getLogger().info("A player died! Consecutive deaths: " + this.consecutiveDeaths);
        int maxDeaths = this.getConfig().getInt("max-consecutive-deaths", 2);
        if (this.consecutiveDeaths >= maxDeaths) {
            stopRelay();
            this.setGameOver(true);
        } else {
            int penalty = this.getConfig().getInt("death-penalty-skips", 2);
            if (penalty > 0) {
                deathSkips.put(this.getCurrentPlayerUUID(), penalty + 1);
            }
            this.rotatePlayer(false);
        }
    }

    /**
     * Handles all logic when a player quits the game.
     * This is called by the GameListener.
     * @param player The player who quit.
     */
    public void handlePlayerQuit(Player player) {
        UUID playerUUID = player.getUniqueId();
        boolean wasActivePlayer = playerUUID.equals(getCurrentPlayerUUID());

        // If the active player is the one quitting, their state must be saved.
        // updateAndSaveState saves the state of getCurrentPlayer().
        if (wasActivePlayer) {
            getLogger().info("[DEBUG] Active player " + player.getName() + " is quitting. Saving state.");
            updateAndSaveState();
        }

        // Stop all current relay tasks to prepare for re-evaluation.
        stopRelay();

        // Now, remove the player from the queue.
        playerQueue.remove(playerUUID);

        // And re-evaluate the game state based on the new number of players.
        // This will correctly freeze the last player if needed.
        checkAndStartRelay();
    }


    //================================================================================
    // State Management & Spectating
    //================================================================================

    private void updateAndSaveState() {
        Player playerToSave = getCurrentPlayer();
        if (playerToSave != null && playerToSave.isOnline()) {
            this.lastActivePlayer = playerToSave;
            saveStateToPDC(playerToSave);
        }
    }

    private void applyPlayerState() {
        Player activePlayer = getCurrentPlayer();
        if (activePlayer == null) {
            if (!playerQueue.isEmpty()) rotatePlayer(false);
            else stopRelay();
            return;
        }

        activePlayer.setGameMode(GameMode.SURVIVAL);
        boolean isLiveRotation = (lastActivePlayer != null && lastActivePlayer.isOnline());

        if (isLiveRotation) {
            getLogger().info("[DEBUG] Live rotation detected. Teleporting " + activePlayer.getName() + " to " + lastActivePlayer.getName() + "'s location.");
            clearFreezeEffects(lastActivePlayer);
            activePlayer.teleport(lastActivePlayer.getLocation());
            syncFromPlayer(activePlayer, lastActivePlayer);
        } else if (loadedState != null) {
            getLogger().info("[DEBUG] New session detected. Applying saved state to " + activePlayer.getName());
            // Teleport safely across dimensions first
            if (loadedState.location != null) {
                Location targetLocation = loadedState.location;
                World targetWorld = targetLocation.getWorld();

                if (targetWorld != null) {
                    getLogger().info("[DEBUG] Preparing to teleport " + activePlayer.getName() + " to saved PDC location in world '" + targetWorld.getName() + "'");
                    // Ensure chunk is loaded before teleporting for safety across dimensions.
                    targetWorld.getChunkAtAsync(targetLocation).thenAccept(chunk -> {
                        getServer().getScheduler().runTask(this, () -> {
                            activePlayer.teleport(targetLocation);
                            getLogger().info("[DEBUG] Teleport complete for " + activePlayer.getName());
                        });
                    });
                } else {
                    getLogger().warning("[DEBUG] Saved location's world is null. Cannot teleport.");
                }
            }
            // Sync inventory and other stats
            syncFromLoadedState(activePlayer);
        } else {
            getLogger().info("[DEBUG] New session with no saved state. Player will start at their natural spawn point.");
        }

        this.lastActivePlayer = activePlayer;

        for (UUID uuid : playerQueue) {
            Player p = getServer().getPlayer(uuid);
            if (p != null && !p.equals(activePlayer)) {
                forceSpectate(p, activePlayer);
            }
        }
    }

    private void syncFromPlayer(Player newPlayer, Player oldPlayer) {
        getLogger().info("[DEBUG] Syncing state from live player " + oldPlayer.getName() + " to " + newPlayer.getName());
        if (oldPlayer.isGliding()) {
            newPlayer.setGliding(true);
            getLogger().info("[DEBUG] Synced gliding state: true");
        }
        newPlayer.setVelocity(oldPlayer.getVelocity());
        newPlayer.setHealth(oldPlayer.getHealth());
        newPlayer.setFoodLevel(oldPlayer.getFoodLevel());
        newPlayer.setSaturation(oldPlayer.getSaturation());
        newPlayer.setTotalExperience(oldPlayer.getTotalExperience());
        newPlayer.getInventory().setContents(oldPlayer.getInventory().getContents());
        newPlayer.getEnderChest().setContents(oldPlayer.getEnderChest().getContents());
        newPlayer.getActivePotionEffects().forEach(effect -> newPlayer.removePotionEffect(effect.getType()));
        oldPlayer.getActivePotionEffects().forEach(newPlayer::addPotionEffect);
    }

    private void syncFromLoadedState(Player newPlayer) {
        getLogger().info("[DEBUG] Syncing state from PDC to new player " + newPlayer.getName());
        newPlayer.setHealth(loadedState.health);
        newPlayer.setFoodLevel(loadedState.foodLevel);
        newPlayer.setSaturation(loadedState.saturation);
        newPlayer.setTotalExperience(loadedState.experience);
        newPlayer.getInventory().setContents(loadedState.inventory);
        newPlayer.getEnderChest().setContents(loadedState.enderChest);
        newPlayer.getActivePotionEffects().forEach(effect -> newPlayer.removePotionEffect(effect.getType()));
        if (loadedState.potionEffects != null) {
            loadedState.potionEffects.forEach(newPlayer::addPotionEffect);
        }
    }

    /**
     * Reliably forces a player to spectate a target using a chain of delayed tasks
     * to prevent client-side race conditions.
     *
     * @param spectator The player who will be spectating.
     * @param target The player to be spectated.
     */

    public void forceSpectate(Player spectator, Player target) {
        if (spectator == null || target == null || !spectator.isOnline() || !target.isOnline()) {
            return; // Don't do anything if players are invalid or offline
        }

        // Step 1: Set the gamemode to spectator.
        spectator.setGameMode(GameMode.SPECTATOR);

        // Try to do it quickly, for better UX
        getServer().getScheduler().runTaskLater(this, () -> {
            if (spectator.isOnline() && target.isOnline()) {
                spectator.setSpectatorTarget(null);
                spectator.setSpectatorTarget(target);
            }
        }, 1L); // delay for the final lock.

        // Backup system:
        // Step 2: After a short delay, teleport the spectator to the target.
        // This helps the client acknowledge the target's location.
        getServer().getScheduler().runTaskLater(this, () -> {
            if (spectator.isOnline() && target.isOnline()) {
                spectator.teleport(target.getLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN);
                // Step 3: After another short delay, set the actual spectator target.
                // By now, the client has processed the gamemode and teleport.
                getServer().getScheduler().runTaskLater(this, () -> {
                    if (spectator.isOnline() && target.isOnline()) {
                        spectator.setSpectatorTarget(null);
                        spectator.setSpectatorTarget(target);
                    }
                }, 5L); // delay for the final lock.

                // Do it again afterwards just in case
                getServer().getScheduler().runTaskLater(this, () -> {
                    if (spectator.isOnline() && target.isOnline()) {
                        spectator.setSpectatorTarget(null);
                        spectator.setSpectatorTarget(target);
                    }
                }, 15L); // delay for the final lock.
            }
        }, 5L); // delay before teleporting.
    }


    private void startInventorySyncTask() {
        if (inventorySyncTask != null) {
            inventorySyncTask.cancel();
        }
        this.inventorySyncTask = getServer().getScheduler().runTaskTimer(this, () -> {
            Player active = getCurrentPlayer();
            if (active == null || !active.isOnline()) {
                return;
            }

            ItemStack[] contents = active.getInventory().getContents();
            ItemStack[] armor = active.getInventory().getArmorContents();

            for (UUID spectatorId : playerQueue) {
                if (spectatorId.equals(active.getUniqueId())) {
                    continue;
                }
                Player spectator = getServer().getPlayer(spectatorId);
                if (spectator != null && spectator.isOnline() && spectator.getGameMode() == GameMode.SPECTATOR) {
                    spectator.getInventory().setContents(contents);
                    spectator.getInventory().setArmorContents(armor);
                }
            }
        }, 0L, 10L);
    }

    /**
     * Freezes a player in place when they are the only one online, waiting for others.
     * @param player The player to freeze.
     */
    private void freezePlayer(Player player) {
        getLogger().info("[DEBUG] Only one player online. Freezing " + player.getName() + " and waiting for more players.");

        // Reload state fresh from PDC to ensure it's not stale.
        loadStateFromPDC();

        // Sync state and teleport first
        if (loadedState != null) {
            if (loadedState.location != null) {
                player.teleport(loadedState.location);
            }
            syncFromLoadedState(player);
        } else {
            // If no state, clear their inventory to prevent item exploits while waiting
            player.getInventory().clear();
        }

        // Set gamemode to adventure to prevent breaking/placing blocks
        player.setGameMode(GameMode.ADVENTURE);

        // Apply potion effects to prevent movement
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 255, false, false, false));
        //player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, Integer.MAX_VALUE, 128, false, false, false));

        final Location freezeLocation = player.getLocation().clone(); // Clone to store the exact location and view angle

        // Task to keep them frozen in place and looking forward, and show waiting message
        this.freezeTask = getServer().getScheduler().runTaskTimer(this, () -> {
            if (!player.isOnline() || playerQueue.size() > 1) {
                // This task will be cancelled by checkAndStartRelay, which is called on join/quit.
                // This is just a fallback.
                if (this.freezeTask != null) {
                    this.freezeTask.cancel();
                    this.freezeTask = null;
                }
                return;
            }

            // Force position and view
            if (player.getLocation().getX() != freezeLocation.getX() || player.getLocation().getY() != freezeLocation.getY() || player.getLocation().getZ() != freezeLocation.getZ()) {
                player.teleport(freezeLocation, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }

            // Show waiting message
            String waitingMessage = ChatColor.translateAlternateColorCodes('&', getConfig().getString("waiting-message"));
            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    new net.md_5.bungee.api.chat.TextComponent(waitingMessage));

        }, 0L, 2L); // Run every 2 ticks to be very responsive
    }

    /**
     * Removes the freezing potion effects from a player.
     * @param player The player to unfreeze.
     */
    private void clearFreezeEffects(Player player) {
        if (player != null && player.isOnline()) {
            getLogger().info("[DEBUG] Clearing freeze effects for " + player.getName());
            player.removePotionEffect(PotionEffectType.SLOWNESS);
            //player.removePotionEffect(PotionEffectType.JUMP_BOOST);
        }
    }


    //================================================================================
    // PDC-based State Storage
    //================================================================================

    private PersistentDataContainer getPDC() {
        Chunk chunk = getServer().getWorlds().get(0).getChunkAt(0, 0);
        if (!chunk.isLoaded()) {
            chunk.load();
        }
        return chunk.getPersistentDataContainer();
    }

    public void saveStateToPDC(Player player) {
        if (player == null) return;
        getLogger().info("[DEBUG] Saving relay state to PDC from player " + player.getName() + ". Location: " + player.getLocation().toVector());
        PersistentDataContainer pdc = getPDC();
        try {
            pdc.set(healthKey, PersistentDataType.DOUBLE, player.getHealth());
            pdc.set(foodKey, PersistentDataType.INTEGER, player.getFoodLevel());
            pdc.set(saturationKey, PersistentDataType.FLOAT, player.getSaturation());
            pdc.set(experienceKey, PersistentDataType.INTEGER, player.getTotalExperience());
            pdc.set(locationKey, PersistentDataType.STRING, PDCUtils.locationToBase64(player.getLocation()));
            pdc.set(inventoryKey, PersistentDataType.STRING, PDCUtils.itemStackArrayToBase64(player.getInventory().getContents()));
            pdc.set(enderChestKey, PersistentDataType.STRING, PDCUtils.itemStackArrayToBase64(player.getEnderChest().getContents()));
            pdc.set(effectsKey, PersistentDataType.STRING, PDCUtils.potionEffectCollectionToBase64(player.getActivePotionEffects()));
        } catch (IOException e) {
            getLogger().severe("Failed to serialize and save relay state to PDC!");
            e.printStackTrace();
        }
    }

    public void loadStateFromPDC() {
        getLogger().info("[DEBUG] Attempting to load relay state from PDC...");
        PersistentDataContainer pdc = getPDC();
        if (!pdc.has(healthKey, PersistentDataType.DOUBLE)) {
            getLogger().info("[DEBUG] No previous relay state found in world data.");
            this.loadedState = null;
            return;
        }
        try {
            this.loadedState = new SharedState();
            loadedState.health = pdc.get(healthKey, PersistentDataType.DOUBLE);
            loadedState.foodLevel = pdc.get(foodKey, PersistentDataType.INTEGER);
            loadedState.saturation = pdc.get(saturationKey, PersistentDataType.FLOAT);
            loadedState.experience = pdc.get(experienceKey, PersistentDataType.INTEGER);
            loadedState.location = PDCUtils.locationFromBase64(pdc.get(locationKey, PersistentDataType.STRING));
            loadedState.inventory = PDCUtils.base64ToItemStackArray(pdc.get(inventoryKey, PersistentDataType.STRING));
            loadedState.enderChest = PDCUtils.base64ToItemStackArray(pdc.get(enderChestKey, PersistentDataType.STRING));
            loadedState.potionEffects = PDCUtils.base64ToPotionEffectCollection(pdc.get(effectsKey, PersistentDataType.STRING));
            getLogger().info("[DEBUG] Successfully loaded relay state from PDC. Location: " + loadedState.location.toVector());
        } catch (IOException | ClassNotFoundException e) {
            getLogger().severe("Failed to deserialize and load relay state from PDC! The saved data might be from a different Minecraft version.");
            e.printStackTrace();
            this.loadedState = null;
        }
    }

    public void setGameOver(boolean over) {
        PersistentDataContainer pdc = getPDC();
        pdc.set(gameOverKey, PersistentDataType.BYTE, (byte) (over ? 1 : 0));
        if (over) {
            stopRelay();
            getServer().broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "GAME OVER! The hardcore relay has ended.");
            for (Player player : getServer().getOnlinePlayers()) {
                player.setGameMode(GameMode.SPECTATOR);
            }
        }
    }

    public boolean isGameOver() {
        return getPDC().getOrDefault(gameOverKey, PersistentDataType.BYTE, (byte) 0) == 1;
    }

    //================================================================================
    // Getters & Inner Classes
    //================================================================================

    public List<UUID> getPlayerQueue() { return playerQueue; }
    public Player getCurrentPlayer() {
        if (currentPlayerIndex < 0 || currentPlayerIndex >= playerQueue.size()) return null;
        return getServer().getPlayer(playerQueue.get(currentPlayerIndex));
    }
    public UUID getCurrentPlayerUUID() {
        if (currentPlayerIndex < 0 || currentPlayerIndex >= playerQueue.size()) return null;
        return playerQueue.get(currentPlayerIndex);
    }
    public Player getNextPlayer() {
        if (playerQueue.size() <= 1) return null;
        int nextIndex = currentPlayerIndex;
        int attempts = 0;
        do {
            nextIndex = (nextIndex + 1) % playerQueue.size();
            attempts++;
        } while (deathSkips.containsKey(playerQueue.get(nextIndex)) && attempts <= playerQueue.size());
        if (attempts > playerQueue.size()) return null;
        return getServer().getPlayer(playerQueue.get(nextIndex));
    }

    private static class SharedState {
        double health;
        int foodLevel;
        float saturation;
        int experience;
        Location location;
        ItemStack[] inventory;
        ItemStack[] enderChest;
        Collection<PotionEffect> potionEffects;
    }

    private static class PDCUtils {
        public static String itemStackArrayToBase64(ItemStack[] items) throws IOException {
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                 BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
                dataOutput.writeInt(items.length);
                for (ItemStack item : items) {
                    dataOutput.writeObject(item);
                }
                return Base64.getEncoder().encodeToString(outputStream.toByteArray());
            }
        }

        public static ItemStack[] base64ToItemStackArray(String data) throws IOException, ClassNotFoundException {
            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
                 BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
                ItemStack[] items = new ItemStack[dataInput.readInt()];
                for (int i = 0; i < items.length; i++) {
                    items[i] = (ItemStack) dataInput.readObject();
                }
                return items;
            }
        }

        public static String potionEffectCollectionToBase64(Collection<PotionEffect> effects) throws IOException {
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                 BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
                dataOutput.writeObject(new ArrayList<>(effects));
                return Base64.getEncoder().encodeToString(outputStream.toByteArray());
            }
        }

        @SuppressWarnings("unchecked")
        public static Collection<PotionEffect> base64ToPotionEffectCollection(String data) throws IOException, ClassNotFoundException {
            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
                 BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
                return (Collection<PotionEffect>) dataInput.readObject();
            }
        }

        public static String locationToBase64(Location loc) throws IOException {
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                 BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
                dataOutput.writeObject(loc);
                return Base64.getEncoder().encodeToString(outputStream.toByteArray());
            }
        }

        public static Location locationFromBase64(String data) throws IOException, ClassNotFoundException {
            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
                 BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
                return (Location) dataInput.readObject();
            }
        }
    }
}
