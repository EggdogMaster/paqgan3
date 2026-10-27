package com.paq.paqgane.sentient;

import com.google.gson.JsonObject;
import com.paq.paqgane.PaqganeConfig;
import com.paq.paqgane.PaqganeManager;
import com.paq.paqgane.action.ActionExecutor;
import com.paq.paqgane.monitor.Discord;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

/** Executes the sentient_* actions. Op checks for sentient_give happen in ActionExecutor, in code. */
public class SentientActions {

    /** An action requested by a player in chat. */
    public static void run(ServerPlayer player, String type, JsonObject a, boolean op) {
        SentientEntity e = SentientManager.entity(player.getServer());
        if (e == null) {
            PaqganeManager.whisper(player, "my body isnt out right now");
            return;
        }
        switch (type) {
            case "sentient_follow" -> e.followPlayer(player.getUUID());
            case "sentient_come" -> e.comeTo(player.blockPosition());
            case "sentient_stay" -> e.stay();
            case "sentient_wander" -> e.wander();
            case "sentient_mine" -> mine(e, player.getServer(), a, player);
            case "sentient_move" -> {
                SentientEntity.Move m = SentientEntity.Move.byName(ActionExecutor.str(a, "name"));
                if (m != null && !e.useMove(m, e.getTarget())) {
                    PaqganeManager.whisper(player, "not enough cursed energy or its still on cooldown");
                }
            }
            case "sentient_give" -> {
                if (!op) {
                    return;
                }
                ServerPlayer target = ActionExecutor.str(a, "player") == null ? player
                        : player.getServer().getPlayerList().getPlayerByName(ActionExecutor.str(a, "player"));
                if (target == null) {
                    target = player;
                }
                ResourceLocation id = ResourceLocation.tryParse(String.valueOf(ActionExecutor.str(a, "item")));
                Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
                if (item == null || item == Items.AIR) {
                    PaqganeManager.whisper(player, "dont know that item");
                    return;
                }
                int count = Math.max(1, Math.min(ActionExecutor.intOf(a, "count", 1), 2304));
                int given = e.giveTo(target, item, count);
                if (given == 0) {
                    PaqganeManager.whisper(player, "i dont have any of that in my inventory");
                } else {
                    Discord.send("**sentient give** " + player.getGameProfile().getName() + " had the Paqgane body hand "
                            + given + "x " + id + " to " + target.getGameProfile().getName());
                }
            }
            default -> {
            }
        }
    }

    /** One action chosen by the body's own thinking loop. No player is asking, and nothing here hands out items. */
    public static void runAuto(SentientEntity e, MinecraftServer server, JsonObject a) {
        String type = ActionExecutor.str(a, "type");
        if (type == null) {
            return;
        }
        switch (type.toLowerCase()) {
            case "sentient_follow", "sentient_come" -> {
                ServerPlayer nearest = nearestPlayer(e);
                if (nearest != null) {
                    if (type.equalsIgnoreCase("sentient_follow")) {
                        e.followPlayer(nearest.getUUID());
                    } else {
                        e.comeTo(nearest.blockPosition());
                    }
                }
            }
            case "sentient_stay" -> e.stay();
            case "sentient_wander" -> e.wander();
            case "sentient_mine" -> mine(e, server, a, null);
            case "sentient_move" -> {
                SentientEntity.Move m = SentientEntity.Move.byName(ActionExecutor.str(a, "name"));
                if (m != null) {
                    e.useMove(m, e.getTarget());
                }
            }
            default -> {
            }
        }
    }

    @Nullable
    private static ServerPlayer nearestPlayer(SentientEntity e) {
        ServerPlayer best = null;
        double bd = Double.MAX_VALUE;
        for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) {
            if (p.level() != e.level() || p.isSpectator()) {
                continue;
            }
            double d = e.distanceToSqr(p);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private static void mine(SentientEntity e, MinecraftServer server, JsonObject a, @Nullable ServerPlayer asker) {
        Block block = ActionExecutor.block(ActionExecutor.str(a, "block"));
        if (block == null || block == Blocks.AIR) {
            return;
        }
        int count = Math.max(1, Math.min(ActionExecutor.intOf(a, "count", 1), PaqganeConfig.MAX_BLOCKS_PER_ACTION.get()));
        int queued = e.queueMine(block, count);
        if (asker != null) {
            if (queued < 0) {
                PaqganeManager.whisper(asker, "i only mine logs, ores, stone, dirt, sand and gravel");
            } else if (queued == 0) {
                PaqganeManager.whisper(asker, "cant find any " + block.getName().getString().toLowerCase() + " near me");
            }
        }
    }
}
