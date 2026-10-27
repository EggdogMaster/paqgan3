package com.paq.paqgane.client;

import com.paq.paqgane.sentient.SentientEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class SentientRenderer extends GeoEntityRenderer<SentientEntity> {
    public SentientRenderer(EntityRendererProvider.Context context) {
        super(context, new SentientModel());
        this.shadowRadius = 0.5F;
    }
}
