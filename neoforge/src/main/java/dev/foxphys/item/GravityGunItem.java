package dev.foxphys.item;

import net.minecraft.world.item.Item;

/**
 * Gravity gun. Right-click grabs the physics body in the crosshair (or releases it); left-click
 * launches the held body. The behaviour lives in dev.foxphys.Interactions.
 */
public final class GravityGunItem extends Item {
    public GravityGunItem(Properties settings) {
        super(settings);
    }
}
