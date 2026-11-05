package fr.customblock.api;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Appelé quand un joueur casse un bloc CustomBlock. L'annuler empêche la casse ; les drops sont modifiables. */
public final class CustomBlockBreakEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Block block;
    private final String blockId;
    private boolean dropItems;
    private boolean cancelled;

    public CustomBlockBreakEvent(Player player, Block block, String blockId, boolean dropItems) {
        this.player = player;
        this.block = block;
        this.blockId = blockId;
        this.dropItems = dropItems;
    }

    public Player getPlayer() {
        return player;
    }

    public Block getBlock() {
        return block;
    }

    /** L'identifiant du bloc dans config.yml. */
    public String getBlockId() {
        return blockId;
    }

    /** true si le bloc va lâcher ses drops (faux en créatif). */
    public boolean isDropItems() {
        return dropItems;
    }

    public void setDropItems(boolean dropItems) {
        this.dropItems = dropItems;
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
