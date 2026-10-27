package com.paq.paqgane.sentient;

import com.paq.paqgane.ModEntities;
import com.paq.paqgane.PaqganeConfig;
import com.paq.paqgane.PaqganeManager;
import com.paq.paqgane.monitor.Discord;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Turns sentient mode on and off, keeps exactly one body alive while it is on, and respawns it after it dies. */
@Mod.EventBusSubscriber(modid = "paqgane")
public class SentientManager {
    private static final String DATA_NAME = "paqgane_sentient";

    private static UUID current;
    private static long respawnAtMs = 0;
    private static int tickCounter = 0;

    public static SentientData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(SentientData::load, SentientData::new, DATA_NAME);
    }

    public static boolean isEnabled(@Nullable MinecraftServer server) {
        return server != null && PaqganeConfig.SENTIENT_ALLOWED.get() && data(server).enabled;
    }

    @Nullable
    public static SentientEntity entity(@Nullable MinecraftServer server) {
        if (server == null || current == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(current);
            if (e instanceof SentientEntity s && s.isAlive()) {
                return s;
            }
        }
        return null;
    }

    /** On and with a living body in the world. */
    public static boolean isActive(@Nullable MinecraftServer server) {
        return isEnabled(server) && entity(server) != null;
    }

    public static String status(MinecraftServer server) {
        if (!PaqganeConfig.SENTIENT_ALLOWED.get()) {
            return "sentient mode is turned off in the config";
        }
        if (!data(server).enabled) {
            return "sentient mode: off";
        }
        SentientEntity e = entity(server);
        if (e == null) {
            long left = Math.max(0, (respawnAtMs - System.currentTimeMillis()) / 1000);
            return "sentient mode: on, body not in the world right now (respawning in about " + left + "s, needs a player online)";
        }
        return "sentient mode: on, health " + Math.round(e.getHealth()) + "/" + Math.round(e.getMaxHealth())
                + ", cursed energy " + Math.round(e.cursedEnergy()) + "/100, " + e.describeIntent()
                + ", at " + e.level().dimension().location() + " " + e.blockPosition().getX() + " "
                + e.blockPosition().getY() + " " + e.blockPosition().getZ();
    }

    /** Returns a short message for the person who asked. */
    public static String setEnabled(MinecraftServer server, ServerPlayer admin, boolean on) {
        if (!PaqganeConfig.SENTIENT_ALLOWED.get()) {
            return "sentient mode is turned off in the config";
        }
        SentientData d = data(server);
        if (on) {
            d.enabled = true;
            d.admin = admin.getUUID();
            d.setDirty();
            if (entity(server) != null) {
                return "my body is already out there. use /paqgane sentient recall to pull it to you";
            }
            spawn(server, admin);
            PaqganeManager.say(server, "ok im out. say paq to talk to me, on foenem");
            Discord.send("**sentient mode on** turned on by " + admin.getGameProfile().getName());
            return "sentient mode on";
        }
        d.enabled = false;
        d.setDirty();
        SentientEntity e = entity(server);
        if (e != null) {
            e.saveBank();
            e.discard();
        }
        current = null;
        PaqganeManager.say(server, "aight im going back in. later");
        Discord.send("**sentient mode off** turned off by " + admin.getGameProfile().getName());
        return "sentient mode off, my inventory is saved";
    }

    public static String recall(MinecraftServer server, ServerPlayer admin) {
        SentientEntity e = entity(server);
        if (e == null) {
            if (!isEnabled(server)) {
                return "sentient mode is off";
            }
            spawn(server, admin);
            return "my body was gone, spawned a new one next to you";
        }
        if (e.level() == admin.level()) {
            e.moveTo(admin.getX() + 1.0, admin.getY(), admin.getZ() + 1.0, e.getYRot(), 0.0F);
            e.getNavigation().stop();
            return "pulled my body to you";
        }
        e.saveBank();
        e.discard();
        current = null;
        spawn(server, admin);
        return "moved my body to your dimension";
    }

    private static void spawn(MinecraftServer server, ServerPlayer near) {
        ServerLevel level = near.serverLevel();
        SentientEntity e = ModEntities.SENTIENT.get().create(level);
        if (e == null) {
            return;
        }
        Vec3 look = near.getLookAngle();
        e.moveTo(near.getX() + look.x * 2.0, near.getY(), near.getZ() + look.z * 2.0, near.getYRot() + 180.0F, 0.0F);
        e.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                .setBaseValue(PaqganeConfig.SENTIENT_MAX_HEALTH.get());
        e.loadBank(data(server).bank);
        e.setHealth(e.getMaxHealth());
        level.addFreshEntity(e);
        current = e.getUUID();
    }

    public static void onDeath(SentientEntity e, DamageSource source) {
        MinecraftServer server = e.getServer();
        if (server == null) {
            return;
        }
        current = null;
        respawnAtMs = System.currentTimeMillis() + PaqganeConfig.SENTIENT_RESPAWN_SECONDS.get() * 1000L;
        String by = source.getEntity() != null ? source.getEntity().getName().getString() : source.getMsgId();
        PaqganeManager.say(server, "i died. back in a bit on foenem");
        Discord.send("**sentient body died** killed by " + by + " at " + e.level().dimension().location() + " "
                + e.blockPosition().getX() + " " + e.blockPosition().getY() + " " + e.blockPosition().getZ()
                + ". it respawns in " + PaqganeConfig.SENTIENT_RESPAWN_SECONDS.get() + "s");
    }

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++tickCounter < 20) {
            return;
        }
        tickCounter = 0;
        MinecraftServer server = event.getServer();
        if (!isEnabled(server)) {
            return;
        }
        SentientEntity body = entity(server);
        if (body == null) {
            if (System.currentTimeMillis() < respawnAtMs) {
                return;
            }
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) {
                return;
            }
            ServerPlayer near = data(server).admin == null ? null : server.getPlayerList().getPlayer(data(server).admin);
            if (near == null) {
                near = players.get(0);
            }
            spawn(server, near);
            return;
        }
        SentientBrain.tick(server, body);
    }

    /** Anything the body kills counts as its loot, so it knows to pick up the drops. */
    @SubscribeEvent
    public static void kills(LivingDeathEvent event) {
        if (event.getSource().getEntity() instanceof SentientEntity s && !event.getEntity().level().isClientSide) {
            s.markLoot(event.getEntity().position());
        }
    }

    @SubscribeEvent
    public static void leaves(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof SentientEntity s && !event.getLevel().isClientSide()) {
            s.saveBank();
        }
    }
}
