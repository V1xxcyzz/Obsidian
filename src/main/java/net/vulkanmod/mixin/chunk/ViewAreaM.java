package net.vulkanmod.mixin.chunk;

import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ViewArea.class)
public abstract class ViewAreaM {

	@ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 4)
	private static int skipAllocation(int viewDistance) {
		return 0;
	}
}
