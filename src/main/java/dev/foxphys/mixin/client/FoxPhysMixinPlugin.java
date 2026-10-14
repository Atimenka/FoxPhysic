package dev.foxphys.mixin.client;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Applies the Sodium mixins only when Sodium is present; the vanilla dispatcher mixin always applies. Without Sodium (or with a Sodium
 * whose internals differ) the body visuals stay plain block displays, which is the fallback.
 */
public final class FoxPhysMixinPlugin implements IMixinConfigPlugin {
    private static final String SODIUM_RENDERER = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer";

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("SodiumWorldRendererMixin") || mixinClassName.endsWith("SodiumPhysicsBlockMixin")) {
            return FabricLoader.getInstance().isModLoaded("sodium") && targetClassName.equals(SODIUM_RENDERER);
        }
        // Vanilla dispatcher mixin: no mod dependency, applies whenever the client loads it.
        return mixinClassName.endsWith("EntityRenderDispatcherMixin");
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
