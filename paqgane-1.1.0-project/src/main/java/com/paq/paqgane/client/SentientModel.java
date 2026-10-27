package com.paq.paqgane.client;

import com.paq.paqgane.sentient.SentientEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class SentientModel extends GeoModel<SentientEntity> {
    @Override
    public ResourceLocation getModelResource(SentientEntity animatable) {
        return new ResourceLocation("paqgane", "geo/paqgane_sentient.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(SentientEntity animatable) {
        return new ResourceLocation("paqgane", "textures/entity/paqgane_sentient.png");
    }

    @Override
    public ResourceLocation getAnimationResource(SentientEntity animatable) {
        return new ResourceLocation("paqgane", "animations/paqgane_sentient.animation.json");
    }
}
