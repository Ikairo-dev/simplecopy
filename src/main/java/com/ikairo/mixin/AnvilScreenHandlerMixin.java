package com.ikairo.mixin;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.EnchantedBookItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.ForgingScreenHandler;
import net.minecraft.screen.Property;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.apache.commons.lang3.StringUtils;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AnvilScreenHandler.class)
public abstract class AnvilScreenHandlerMixin extends ForgingScreenHandler {

    @Shadow
    private String newItemName;

    @Shadow
    @Final
    private Property levelCost;

    @Shadow public abstract void updateResult();

    @Unique
    private boolean isDuplicating;

    public AnvilScreenHandlerMixin(int syncId, PlayerInventory inventory) {
        super(null, syncId, inventory, null);
    }

    @Inject(method = "updateResult()V", at = @At("HEAD"))
    protected void checkIsDuplicating(CallbackInfo ci) {
        isDuplicating = this.input.getStack(0).isOf(Items.BOOK)
                        && !EnchantedBookItem.getEnchantmentNbt(this.input.getStack(1)).isEmpty();
    }

    @Redirect(method = "updateResult()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/item/ItemStack;getCount()I"))
    private int onGetItemStackCount(ItemStack stack) {
        if (isDuplicating) {
            return 1;
        } else {
            return stack.getCount();
        }
    }

    @ModifyVariable(method = "updateResult()V", at = @At("STORE"), ordinal = 3)
    private boolean onStoreIsEnchantAcceptable(boolean isAcceptable) {
        if (isDuplicating) {
            return true;
        } else {
            return isAcceptable;
        }
    }

    @Inject(method = "updateResult()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/inventory/CraftingResultInventory;setStack(ILnet/minecraft/item/ItemStack;)V", shift = At.Shift.AFTER))
    private void afterSetResult(CallbackInfo ci) {
        if (isDuplicating) {
            ItemStack result = this.input.getStack(1).copy();

            if (!StringUtils.isBlank(this.newItemName)
                    && !StringUtils.equals(this.newItemName, this.input.getStack(0).getName().getString())
                    && !StringUtils.equals(this.newItemName, this.input.getStack(0).getName().getString())) {
                result.setCustomName(Text.literal(this.newItemName));
            }

            this.output.setStack(0, result);
        }
    }

    @Redirect(method = "onTakeOutput", at = @At(value = "INVOKE", target = "Lnet/minecraft/inventory/Inventory;setStack(ILnet/minecraft/item/ItemStack;)V", ordinal = 0))
    private void decrementWhenDuplicating(Inventory instance, int i, ItemStack itemStack) {
        if (this.input.getStack(0).getCount() > 1 && this.input.getStack(0).isOf(Items.BOOK) && !this.input.getStack(1).isEmpty()) {
            if (player instanceof ServerPlayerEntity) {
                this.input.getStack(0).decrement(1);
            }
        } else {
            this.input.setStack(0, ItemStack.EMPTY);
        }
        this.updateResult();
    }

    @Redirect(method = "onTakeOutput", at = @At(value = "INVOKE", target = "Lnet/minecraft/inventory/Inventory;setStack(ILnet/minecraft/item/ItemStack;)V", ordinal = 3))
    private void doNotSetInputSlot2Empty(Inventory instance, int i, ItemStack itemStack) {
        if (!isDuplicating) {
            this.input.setStack(1, ItemStack.EMPTY);
        }
    }

    public ItemStack quickMove(PlayerEntity player, int slot) {
        if (this.input.getStack(0).isOf(Items.BOOK)
                && slot == 2
                && !this.input.getStack(1).isEmpty()
                && !player.getAbilities().creativeMode
                && player.experienceLevel < this.levelCost.get()) {
            return this.input.getStack(slot);
        }
        return super.quickMove(player, slot);
    }
}
