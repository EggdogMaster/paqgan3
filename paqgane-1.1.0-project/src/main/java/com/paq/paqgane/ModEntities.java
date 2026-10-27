package com.paq.paqgane;

import com.paq.paqgane.sentient.SentientEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, "paqgane");

    public static final RegistryObject<EntityType<PaqganeEntity>> PAQGANE = ENTITIES.register("paqgane",
            () -> EntityType.Builder.<PaqganeEntity>of(PaqganeEntity::new, MobCategory.MISC)
                    .sized(0.6F, 0.6F)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .fireImmune()
                    .build("paqgane:paqgane"));

    /** The walking body. Never written to chunks: its state lives in the world's saved data instead. */
    public static final RegistryObject<EntityType<SentientEntity>> SENTIENT = ENTITIES.register("paqgane_sentient",
            () -> EntityType.Builder.<SentientEntity>of(SentientEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.95F)
                    .clientTrackingRange(10)
                    .noSave()
                    .build("paqgane:paqgane_sentient"));

    @Mod.EventBusSubscriber(modid = "paqgane", bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class Events {
        @SubscribeEvent
        public static void attributes(EntityAttributeCreationEvent event) {
            event.put(PAQGANE.get(), PaqganeEntity.createAttributes().build());
            event.put(SENTIENT.get(), SentientEntity.createAttributes().build());
        }
    }
}
