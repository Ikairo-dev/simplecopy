package com.ikairo.mixin;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.block.AnvilBlock;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.ForgingScreenHandler;
import net.minecraft.screen.Property;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.StringHelper;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AnvilScreenHandler.class)
public abstract class AnvilScreenHandlerMixin extends ForgingScreenHandler {

    @Shadow
    private boolean keepSecondSlot;

    @Shadow
    private int repairItemUsage;

    @Shadow
    private String newItemName;

    @Shadow
    public static int getNextCost(int cost) {
        return 0;
    }

    @Final
    private final Property levelCost = Property.create();

    public AnvilScreenHandlerMixin(int syncId, PlayerInventory inventory) {
        super(null, syncId, inventory, null, null);
    }

    @Inject(method = "updateResult", at = @At("HEAD"), cancellable = true)
    protected void onUpdateResult(CallbackInfo ci) {

        ItemStack slot1 = this.input.getStack(0);
        ItemStack slot2 = this.input.getStack(1);
        this.keepSecondSlot = false;
        this.levelCost.set(1);

        if(slot1.isOf(Items.BOOK) && slot2.contains(DataComponentTypes.STORED_ENCHANTMENTS)) {
            ci.cancel();
            this.keepSecondSlot = true;

            int additionalCost = 0;
            long baseCost = 0L;
            int nameCost = 0;
            ItemStack result = slot2.copy();
            ItemEnchantmentsComponent.Builder resultEnchants = new ItemEnchantmentsComponent.Builder(EnchantmentHelper.getEnchantments(result));
//            baseCost += (long)(Integer) slot1.getOrDefault(DataComponentTypes.REPAIR_COST, 0) + (long)(Integer) slot2.getOrDefault(DataComponentTypes.REPAIR_COST, 0); //this can probably just be 0
            this.repairItemUsage = 0; //this can probably be removed
            if (!slot2.isEmpty()) {

                ItemEnchantmentsComponent slot2Enchants = EnchantmentHelper.getEnchantments(slot2);

                for(Object2IntMap.Entry<RegistryEntry<Enchantment>> enchant : slot2Enchants.getEnchantmentEntries()) {
                    RegistryEntry<Enchantment> slot2Enchant = (RegistryEntry) enchant.getKey();
                    int finalEnchantLevel = enchant.getIntValue();
                    Enchantment enchantType = (Enchantment) slot2Enchant.value();

                    if (finalEnchantLevel > enchantType.getMaxLevel()) {
                        finalEnchantLevel = enchantType.getMaxLevel();
                    }

                    resultEnchants.set(slot2Enchant, finalEnchantLevel);
                    int enchantTransferCost = enchantType.getAnvilCost();

                    enchantTransferCost = Math.max(1, enchantTransferCost / 2);
                    additionalCost += enchantTransferCost * finalEnchantLevel;
                }
            }

            if (this.newItemName != null && !StringHelper.isBlank(this.newItemName)) {
                if (!this.newItemName.equals(slot1.getName().getString())) {
                    nameCost = 1;
                    additionalCost += nameCost;
                    result.set(DataComponentTypes.CUSTOM_NAME, Text.literal(this.newItemName));
                }
            } else if (slot1.contains(DataComponentTypes.CUSTOM_NAME)) {
                nameCost = 1;
                additionalCost += nameCost;
                result.remove(DataComponentTypes.CUSTOM_NAME);
            }

            int finalCost = additionalCost <= 0 ? 0 : (int) MathHelper.clamp(baseCost + (long) additionalCost, 0L, 2147483647L);
            this.levelCost.set(finalCost);
            if (additionalCost <= 0) {
                result = ItemStack.EMPTY;
            }

            if (nameCost == additionalCost && nameCost > 0) {
                if (this.levelCost.get() >= 40) {
                    this.levelCost.set(39);
                }

                this.keepSecondSlot = true;
            }

            if (this.levelCost.get() >= 40 && !this.player.isInCreativeMode()) {
                result = ItemStack.EMPTY;
            }

            if (!result.isEmpty()) {
                int finalRepairCost = (Integer) result.getOrDefault(DataComponentTypes.REPAIR_COST, 0);
                if (finalRepairCost < (Integer) slot2.getOrDefault(DataComponentTypes.REPAIR_COST, 0)) {
                    finalRepairCost = (Integer) slot2.getOrDefault(DataComponentTypes.REPAIR_COST, 0);
                }

                if (nameCost != additionalCost || nameCost == 0) {
                    finalRepairCost = getNextCost(finalRepairCost);
                }

                result.set(DataComponentTypes.REPAIR_COST, finalRepairCost);
                EnchantmentHelper.set(result, resultEnchants.build());
            }

            this.output.setStack(0, result);
            this.sendContentUpdates();
        }
    }

    @Inject(method = "onTakeOutput", at = @At("HEAD"), cancellable = true)
    protected void onOnTakeOutput(PlayerEntity player, ItemStack stack, CallbackInfo ci) {
        ItemStack slot1 = this.input.getStack(0);
        if (slot1.getCount() > 1 && slot1.isOf(Items.BOOK) && !this.input.getStack(1).isEmpty()) {
            if (!player.isInCreativeMode()) {
                player.addExperienceLevels(-this.levelCost.get());
            }

            if (this.repairItemUsage > 0) {
                ItemStack itemStack = this.input.getStack(1);
                if (!itemStack.isEmpty() && itemStack.getCount() > this.repairItemUsage) {
                    itemStack.decrement(this.repairItemUsage);
                    this.input.setStack(1, itemStack);
                } else {
                    this.input.setStack(1, ItemStack.EMPTY);
                }
            } else if (!this.keepSecondSlot) {
                this.input.setStack(1, ItemStack.EMPTY);
            }

            if (player instanceof ServerPlayerEntity serverPlayerEntity) {
                if (!StringHelper.isBlank(this.newItemName) && !this.input.getStack(0).getName().getString().equals(this.newItemName)) {
                    serverPlayerEntity.getTextStream().filterText(this.newItemName);
                }
            }

            this.input.getStack(0).decrement(1);
            this.context.run((world, pos) -> {
                BlockState blockState = world.getBlockState(pos);
                if (!player.isInCreativeMode() && blockState.isIn(BlockTags.ANVIL) && player.getRandom().nextFloat() < 0.12F) {
                    BlockState blockState2 = AnvilBlock.getLandingState(blockState);
                    if (blockState2 == null) {
                        world.removeBlock(pos, false);
                        world.syncWorldEvent(1029, pos, 0);
                    } else {
                        world.setBlockState(pos, blockState2, 2);
                        world.syncWorldEvent(1030, pos, 0);
                    }
                } else {
                    world.syncWorldEvent(1030, pos, 0);
                }

            });
            this.updateResult();
            ci.cancel();
        }
    }

    public ItemStack quickMove(PlayerEntity player, int slot) {
        if (this.input.getStack(0).isOf(Items.BOOK)
                && slot == 2
                && !this.input.getStack(1).isEmpty()
                && !player.isInCreativeMode()
                && player.experienceLevel < this.levelCost.get()) {
            return this.input.getStack(slot);
        }
        return super.quickMove(player, slot);
    }
}
