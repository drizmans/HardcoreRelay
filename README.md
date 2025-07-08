Of course, here is the README content in Markdown format.

# Hardcore Relay Plugin

## Overview

**Hardcore Relay** is a unique and challenging multiplayer gamemode for PaperMC servers. It transforms the classic hardcore experience into a cooperative, high-stakes relay race where a group of players shares control of a single character. With a shared body, inventory, and a limited number of lives, every player's turn matters. One mistake can have consequences for the entire team, making communication and strategy essential for survival.

The plugin is designed to be self-contained and easy to manage. The entire game state, including the shared character's inventory, location, and game-over status, is tied directly to the world data. This means a fresh start is as simple as creating a new world.

-----

## Features

* **Timed Relay:** Control of a single shared character rotates through all online players at a configurable interval.
* **Shared Body:** Health, hunger, saturation, experience, potion effects, and remaining air are synchronized across all players and persist through rotations. The inventory and Ender Chest are also shared.
* **Seamless Transitions:** Player momentum, velocity, and even elytra gliding are carried over between turns, ensuring that high-speed movements and falls continue uninterrupted.
* **Consecutive Death System:** The game ends for everyone if a configurable number of players "die" in succession. A successful turn completion by any player resets this death counter.
* **True Hardcore Death:** The plugin cleverly intercepts fatal damage, preventing the death screen from ever appearing. Instead, it triggers the death penalty and rotation instantly, keeping the game flowing.
* **Dynamic Player Queue:** Players who join mid-game are added to the end of the rotation queue. Players who leave are removed. If the active player leaves, control is instantly passed to the next player in line.
* **Locked Spectator Mode:** All non-active players are locked into a first-person spectator view of the active player, ensuring they are always part of the action. This view persists seamlessly across dimension changes.
* **Live Inventory Sync:** Spectators can open their inventory at any time to see a live, real-time view of the active player's inventory and armor.
* **UI Notifications:** A persistent action bar message keeps spectators informed of the shared character's stats, the time remaining in the current turn, and who's up next. The active player sees a countdown only in the last 10 seconds.
* **Game Over State:** When the world ends, all players are permanently put into spectator mode. This state is saved to the world's data, allowing for an easy reset by simply deleting the world folder.
* **Single Player Freeze:** If only one player is online, the relay pauses. That player is synced to the last known state of the character but is frozen in place, unable to move or interact, until another player joins to resume the relay.

-----

## Installation

1.  Download the latest `HardcoreRelay.jar` file.
2.  Place the `.jar` file into your server's `/plugins` directory.
3.  Restart or reload your server.
4.  The plugin will generate a `config.yml` file in `/plugins/HardcoreRelay/`.

-----

## Configuration

The `config.yml` file allows you to customize the core mechanics of the relay.

```yaml
# Hardcore Relay Plugin Configuration

# The duration of each player's turn in seconds.
# Default: 600 (10 minutes)
turn-duration: 600

# The number of consecutive player deaths allowed before the world ends.
# Set to 1 for a classic "first death ends the game" hardcore experience.
# Default: 2
max-consecutive-deaths: 2

# The number of turns a player skips after dying (if the game doesn't end).
# Default: 2
death-penalty-skips: 2

# If true, the game will pause if only one player is online.
# Default: true
wait-for-more-players: true

# The message displayed on the action bar when waiting for more players.
waiting-message: "&cWaiting for more players to join the relay..."

# --- UI Customization ---

# Action bar message format for spectators.
# Placeholders: {health}, {hunger}, {time}, {next_player}
spectator-message: "&cHP: &f{health} &c| Hunger: &f{hunger} &c| Time: &f{time} &c| Next: &f{next_player}"

# Action bar message for the active player during the final seconds.
# Placeholder: {time}
active-player-countdown-message: "&4! Switching to {next_player} in {time} !"
```

-----

## How to Play

1.  **Join the Server:** As soon as two or more players are online, the relay will automatically begin.
2.  **The First Player:** The first player in the queue takes control of the shared character.
3.  **Spectators:** All other players are automatically put into spectator mode, locked to the active player's view.
4.  **Rotation:** When the timer runs out, control is passed to the next player in the queue. The outgoing player is put into spectator mode, and the new player takes over seamlessly at the exact same location with the same inventory, health, and momentum.
5.  **Survive:** Work together to gather resources, defeat bosses, and survive as long as possible\!

-----

## Resetting the Game

The plugin is designed to be world-specific. All game progress (inventory, location, game-over state) is stored within the world's data itself. To start a completely new Hardcore Relay session:

1.  Stop your server.
2.  Delete the world folder(s) (e.g., `world`, `world_nether`, `world_the_end`).
3.  Start the server again. A new world will be generated, and the plugin will start fresh.

-----

## Commands & Permissions

This plugin is designed for simplicity and requires no setup. There are **no commands** and **no permissions** to configure. All functionality is automatic.