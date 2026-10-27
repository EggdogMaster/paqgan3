package com.paq.paqgane.sentient;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/** Persistent state of sentient mode: whether it is on and what the body carries. The body entity itself is never saved to chunks. */
public class SentientData extends SavedData {
    public boolean enabled;
    public UUID admin;
    public CompoundTag bank = new CompoundTag();

    public static SentientData load(CompoundTag tag) {
        SentientData d = new SentientData();
        d.enabled = tag.getBoolean("enabled");
        if (tag.hasUUID("admin")) {
            d.admin = tag.getUUID("admin");
        }
        d.bank = tag.getCompound("bank");
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("enabled", enabled);
        if (admin != null) {
            tag.putUUID("admin", admin);
        }
        tag.put("bank", bank);
        return tag;
    }
}
