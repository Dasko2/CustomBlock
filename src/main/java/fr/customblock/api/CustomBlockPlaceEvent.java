package fr.customblock.api;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Appelé quand un joueur pose un bloc CustomBlock. L'annuler empêche la pose. */
public final class CustomBlockPlaceEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Block block;
    private final String blockId;
    private boolean cancelled;

    public CustomBlockPlaceEvent(Player player, Block block, String blockId) {
        this.player = player;
        this.block = block;
        this.blockId = blockId;
    }

    public Player getPlayer() {
        return player;
    }

    /** L'emplacement du bloc posé. */
    public Block getBlock() {
        return block;
    }

    /** L'identifiant du bloc dans config.yml. */
    public String getBlockId() {
        return blockId;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
