package com.paq.paqgane.sentient;

import com.paq.paqgane.PaqganeConfig;
import com.paq.paqgane.PaqganeManager;
import com.paq.paqgane.ai.Brain;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;
import net.minecraftforge.items.ItemHandlerHelper;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * The walking, player-shaped body of Paqgane. It has its own inventory, auto-equips gear, fights hostile mobs
 * and uses Cursed Fate style moves that are implemented by Paqgane itself (they do not call the Cursed Fate mod).
 */
public class SentientEntity extends PathfinderMob implements GeoEntity {

    public enum Intent {
        WANDER, FOLLOW, STAY, GOTO, MINE
    }

    public enum Move {
        BLACK_FLASH("black_flash", 10, 80),
        HOLLOW_PURPLE("hollow_purple", 40, 300),
        REVERSE("reverse", 30, 400),
        DOMAIN("domain", 80, 2400);

        public final String id;
        public final int cost;
        public final int cooldownTicks;

        Move(String id, int cost, int cooldownTicks) {
            this.id = id;
            this.cost = cost;
            this.cooldownTicks = cooldownTicks;
        }

        @Nullable
        public static Move byName(@Nullable String name) {
            if (name == null) {
                return null;
            }
            String n = name.toLowerCase().replace(' ', '_');
            for (Move m : values()) {
                if (m.id.equals(n) || m.name().equalsIgnoreCase(n)) {
                    return m;
                }
            }
            return null;
        }
    }

    private static final EntityDataAccessor<Integer> CAST_TICKS =
            SynchedEntityData.defineId(SentientEntity.class, EntityDataSerializers.INT);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.paqgane_sentient.idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.paqgane_sentient.walk");
    private static final RawAnimation ATTACK = RawAnimation.begin().thenPlay("animation.paqgane_sentient.attack");
    private static final RawAnimation CAST = RawAnimation.begin().thenLoop("animation.paqgane_sentient.cast");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final SimpleContainer inventory = new SimpleContainer(36);
    private boolean bankDirty;

    private Intent intent = Intent.WANDER;
    private UUID followId;
    private BlockPos gotoPos;
    private final ArrayDeque<BlockPos> mineQueue = new ArrayDeque<>();
    private int mineDelay;
    private int mineStall;

    private float cursedEnergy = 100.0F;
    private final EnumMap<Move, Integer> readyAt = new EnumMap<>(Move.class);
    private int domainTicks;
    private BlockPos domainCenter;

    private int lootUntilTick;
    private Vec3 lootPos;
    private int lastThanksTick = -1000;

    public SentientEntity(EntityType<? extends SentientEntity> type, Level level) {
        super(type, level);
        this.setCustomName(Component.literal("Paqgane"));
        this.setCustomNameVisible(true);
        this.setPersistenceRequired();
        this.inventory.addListener(c -> this.bankDirty = true);
        if (this.getNavigation() instanceof GroundPathNavigation nav) {
            nav.setCanOpenDoors(true);
            nav.setCanFloat(true);
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        // config values are not readable yet when attributes are registered, so max health is set again on spawn
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 40.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.ATTACK_KNOCKBACK, 0.4)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(CAST_TICKS, 0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.25D, true));
        this.goalSelector.addGoal(2, new IntentGoal(this));
        this.goalSelector.addGoal(3, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(4, new WanderGoal(this));
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Monster.class, 10, true, false,
                e -> !(e instanceof NeutralMob)));
    }

    // ------------------------------------------------------------------ public control api

    public SimpleContainer inventory() {
        return this.inventory;
    }

    public float cursedEnergy() {
        return this.cursedEnergy;
    }

    public Intent intent() {
        return this.intent;
    }

    public void followPlayer(UUID id) {
        this.followId = id;
        this.intent = Intent.FOLLOW;
        this.mineQueue.clear();
    }

    public void comeTo(BlockPos pos) {
        this.gotoPos = pos;
        this.intent = Intent.GOTO;
        this.mineQueue.clear();
    }

    public void stay() {
        this.intent = Intent.STAY;
        this.mineQueue.clear();
        this.getNavigation().stop();
    }

    public void wander() {
        this.intent = Intent.WANDER;
        this.mineQueue.clear();
    }

    public String describeIntent() {
        switch (this.intent) {
            case FOLLOW:
                return "following a player";
            case STAY:
                return "standing still";
            case GOTO:
                return "walking to a spot";
            case MINE:
                return "mining (" + this.mineQueue.size() + " blocks left)";
            default:
                return "wandering around";
        }
    }

    public boolean canMine(BlockState s, BlockPos pos) {
        if (!PaqganeConfig.SENTIENT_MINING.get() || s.isAir() || s.hasBlockEntity()) {
            return false;
        }
        if (s.getDestroySpeed(this.level(), pos) < 0.0F) {
            return false;
        }
        return s.is(BlockTags.LOGS) || s.is(Tags.Blocks.ORES) || s.is(BlockTags.BASE_STONE_OVERWORLD)
                || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL);
    }

    /** Queues up to count blocks of that type near the body. Returns how many were queued. */
    public int queueMine(Block block, int count) {
        if (!canMine(block.defaultBlockState(), this.blockPosition())) {
            return -1;
        }
        BlockPos center = this.blockPosition();
        List<BlockPos> found = new java.util.ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-16, -8, -16), center.offset(16, 8, 16))) {
            if (this.level().getBlockState(pos).getBlock() == block) {
                found.add(pos.immutable());
            }
        }
        found.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
        this.mineQueue.clear();
        int n = Math.min(count, found.size());
        for (int i = 0; i < n; i++) {
            this.mineQueue.add(found.get(i));
        }
        if (n > 0) {
            this.intent = Intent.MINE;
            this.mineStall = 0;
            this.markLoot(Vec3.atCenterOf(found.get(0)));
        }
        return n;
    }

    /** Hands items from the body's own inventory to a player. Returns how many were handed over. */
    public int giveTo(ServerPlayer target, Item item, int count) {
        int given = 0;
        for (int i = 0; i < this.inventory.getContainerSize() && given < count; i++) {
            ItemStack s = this.inventory.getItem(i);
            if (!s.isEmpty() && s.getItem() == item) {
                int take = Math.min(s.getCount(), count - given);
                ItemStack out = s.split(take);
                this.inventory.setItem(i, s.isEmpty() ? ItemStack.EMPTY : s);
                ItemHandlerHelper.giveItemToPlayer(target, out);
                given += take;
            }
        }
        return given;
    }

    public void markLoot(Vec3 pos) {
        this.lootPos = pos;
        this.lootUntilTick = this.tickCount + 200;
    }

    public int castTicks() {
        return this.entityData.get(CAST_TICKS);
    }

    // ------------------------------------------------------------------ bank (saved state)

    public CompoundTag createBank() {
        CompoundTag t = new CompoundTag();
        t.put("inv", this.inventory.createTag());
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            CompoundTag e = new CompoundTag();
            this.getItemBySlot(slot).save(e);
            t.put("eq_" + slot.getName(), e);
        }
        t.putFloat("ce", this.cursedEnergy);
        return t;
    }

    public void loadBank(CompoundTag t) {
        if (t == null || t.isEmpty()) {
            return;
        }
        this.inventory.fromTag(t.getList("inv", Tag.TAG_COMPOUND));
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            String key = "eq_" + slot.getName();
            if (t.contains(key)) {
                this.setItemSlot(slot, ItemStack.of(t.getCompound(key)));
            }
        }
        if (t.contains("ce")) {
            this.cursedEnergy = t.getFloat("ce");
        }
        this.bankDirty = false;
    }

    public void saveBank() {
        if (this.level().isClientSide || this.getServer() == null) {
            return;
        }
        SentientData d = SentientManager.data(this.getServer());
        d.bank = this.createBank();
        d.setDirty();
        this.bankDirty = false;
    }

    // ------------------------------------------------------------------ ticking

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        ServerLevel sl = (ServerLevel) this.level();
        int t = this.tickCount;

        int cast = this.entityData.get(CAST_TICKS);
        if (cast > 0) {
            this.entityData.set(CAST_TICKS, cast - 1);
        }
        if (t % 20 == 0) {
            this.cursedEnergy = Math.min(100.0F, this.cursedEnergy + 1.5F);
        }
        if (t % 10 == 0) {
            this.pickupItems(sl);
            this.autoMoves(sl);
        }
        if (t % 40 == 0) {
            this.equipBest();
            if (this.bankDirty) {
                this.saveBank();
            }
        }
        if (t % 100 == 0) {
            this.autoEat();
        }
        if (this.domainTicks > 0) {
            this.tickDomain(sl);
        }
    }

    private void intentTick() {
        ServerLevel sl = (ServerLevel) this.level();
        switch (this.intent) {
            case FOLLOW -> {
                ServerPlayer t = this.followId == null ? null : sl.getServer().getPlayerList().getPlayer(this.followId);
                if (t == null || t.level() != this.level() || !t.isAlive()) {
                    this.intent = Intent.WANDER;
                    return;
                }
                double d2 = this.distanceToSqr(t);
                if (d2 > 48.0 * 48.0) {
                    this.moveTo(t.getX() + 1.0, t.getY(), t.getZ() + 1.0, this.getYRot(), 0.0F);
                    this.getNavigation().stop();
                } else if (d2 > 16.0) {
                    this.getNavigation().moveTo(t, 1.2D);
                } else if (d2 < 9.0) {
                    this.getNavigation().stop();
                }
            }
            case GOTO -> {
                if (this.gotoPos == null || this.distanceToSqr(Vec3.atCenterOf(this.gotoPos)) < 4.0) {
                    this.intent = Intent.STAY;
                    this.getNavigation().stop();
                } else if (this.getNavigation().isDone() || this.tickCount % 20 == 0) {
                    this.getNavigation().moveTo(this.gotoPos.getX() + 0.5, this.gotoPos.getY(), this.gotoPos.getZ() + 0.5, 1.0D);
                }
            }
            case MINE -> this.mineTick();
            default -> {
            }
        }
    }

    private void mineTick() {
        BlockPos p = this.mineQueue.peek();
        if (p == null) {
            this.intent = Intent.WANDER;
            return;
        }
        BlockState s = this.level().getBlockState(p);
        if (s.isAir() || !this.canMine(s, p)) {
            this.mineQueue.poll();
            return;
        }
        double d2 = this.distanceToSqr(Vec3.atCenterOf(p));
        if (d2 > 12.0) {
            if (this.tickCount % 10 == 0 || this.getNavigation().isDone()) {
                this.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 1.0D);
            }
            if (++this.mineStall > 400) {
                this.mineQueue.poll();
                this.mineStall = 0;
            }
            return;
        }
        this.getNavigation().stop();
        this.getLookControl().setLookAt(Vec3.atCenterOf(p));
        if (--this.mineDelay > 0) {
            return;
        }
        this.mineDelay = 8;
        this.mineStall = 0;
        this.swing(InteractionHand.MAIN_HAND);
        this.level().destroyBlock(p, true, this);
        this.markLoot(Vec3.atCenterOf(p));
        this.mineQueue.poll();
    }

    private void pickupItems(ServerLevel sl) {
        if (!PaqganeConfig.SENTIENT_PICKUP.get()) {
            return;
        }
        boolean lootWindow = this.tickCount < this.lootUntilTick && this.lootPos != null;
        ItemEntity nearestLoot = null;
        double nearestD = Double.MAX_VALUE;
        for (ItemEntity ie : sl.getEntitiesOfClass(ItemEntity.class, this.getBoundingBox().inflate(8.0, 3.0, 8.0))) {
            if (ie.isRemoved() || ie.getItem().isEmpty()) {
                continue;
            }
            boolean gift = PaqganeConfig.SENTIENT_ACCEPT_GIFTS.get() && wasThrownByPlayer(ie);
            boolean loot = lootWindow && ie.position().distanceToSqr(this.lootPos) < 100.0;
            if (!gift && !loot) {
                continue;
            }
            double d = this.distanceToSqr(ie);
            if (d <= 6.25) {
                ItemStack rest = this.inventory.addItem(ie.getItem().copy());
                if (rest.isEmpty()) {
                    ie.discard();
                } else {
                    ie.setItem(rest);
                }
            } else if (d < nearestD) {
                nearestD = d;
                nearestLoot = ie;
            }
        }
        if (nearestLoot != null && this.getTarget() == null && this.intent == Intent.WANDER) {
            this.getNavigation().moveTo(nearestLoot, 1.1D);
        }
    }

    private static boolean wasThrownByPlayer(ItemEntity ie) {
        CompoundTag tag = ie.saveWithoutId(new CompoundTag());
        return tag.contains("Thrower");
    }

    // ------------------------------------------------------------------ gear and food

    private static double armorScore(ItemStack s) {
        if (s.getItem() instanceof ArmorItem a) {
            return a.getDefense() + a.getToughness();
        }
        return 0.0;
    }

    private static double weaponScore(ItemStack s) {
        if (s.isEmpty()) {
            return 0.0;
        }
        Item it = s.getItem();
        if (!(it instanceof SwordItem) && !(it instanceof AxeItem) && !(it instanceof TridentItem)) {
            return 0.0;
        }
        double sum = 0.0;
        for (AttributeModifier m : s.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE)) {
            sum += m.getAmount();
        }
        return sum;
    }

    private void equipBest() {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack cur = this.getItemBySlot(slot);
            double best = armorScore(cur);
            int idx = -1;
            for (int i = 0; i < this.inventory.getContainerSize(); i++) {
                ItemStack s = this.inventory.getItem(i);
                if (s.getItem() instanceof ArmorItem a && a.getEquipmentSlot() == slot && armorScore(s) > best) {
                    best = armorScore(s);
                    idx = i;
                }
            }
            if (idx >= 0) {
                ItemStack take = this.inventory.getItem(idx);
                this.inventory.setItem(idx, cur);
                this.setItemSlot(slot, take);
                this.bankDirty = true;
            }
        }
        ItemStack hand = this.getItemBySlot(EquipmentSlot.MAINHAND);
        double bestW = weaponScore(hand);
        int widx = -1;
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            double sc = weaponScore(this.inventory.getItem(i));
            if (sc > bestW) {
                bestW = sc;
                widx = i;
            }
        }
        if (widx >= 0) {
            ItemStack take = this.inventory.getItem(widx);
            this.inventory.setItem(widx, hand);
            this.setItemSlot(EquipmentSlot.MAINHAND, take);
            this.bankDirty = true;
        }
    }

    /** Puts everything it wears back into its inventory (overflow is dropped). Used by the op command. */
    public void unequipAll() {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = this.getItemBySlot(slot);
            if (!s.isEmpty()) {
                ItemStack rest = this.inventory.addItem(s.copy());
                this.setItemSlot(slot, ItemStack.EMPTY);
                if (!rest.isEmpty()) {
                    this.spawnAtLocation(rest);
                }
            }
        }
        this.bankDirty = true;
    }

    private void autoEat() {
        if (this.getHealth() >= this.getMaxHealth() * 0.7F) {
            return;
        }
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack s = this.inventory.getItem(i);
            if (!s.isEmpty() && s.isEdible() && s.getItem().getFoodProperties() != null) {
                int nutrition = s.getItem().getFoodProperties().getNutrition();
                s.shrink(1);
                this.inventory.setItem(i, s.isEmpty() ? ItemStack.EMPTY : s);
                this.heal(Math.max(2.0F, nutrition * 0.8F));
                this.playSound(SoundEvents.GENERIC_EAT, 1.0F, 1.0F);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ cursed moves

    private boolean ready(Move m) {
        return this.cursedEnergy >= m.cost && this.tickCount >= this.readyAt.getOrDefault(m, 0);
    }

    private boolean validVictim(LivingEntity v) {
        if (v == this || !v.isAlive()) {
            return false;
        }
        if (v instanceof Enemy) {
            return true;
        }
        return PaqganeConfig.SENTIENT_MOVES_HIT_PLAYERS.get() && v instanceof Player && v == this.getTarget();
    }

    private void autoMoves(ServerLevel sl) {
        if (this.domainTicks > 0) {
            return;
        }
        LivingEntity target = this.getTarget();
        boolean low = this.getHealth() < this.getMaxHealth() * 0.5F;
        if (low && this.ready(Move.REVERSE)) {
            this.useMove(Move.REVERSE, null);
            return;
        }
        if (target == null || !target.isAlive()) {
            return;
        }
        int hostiles = sl.getEntitiesOfClass(Monster.class, this.getBoundingBox().inflate(10.0)).size();
        double d = this.distanceTo(target);
        if ((hostiles >= 3 || (low && hostiles >= 1)) && this.ready(Move.DOMAIN)) {
            this.useMove(Move.DOMAIN, null);
        } else if (d <= 4.0 && this.ready(Move.BLACK_FLASH) && this.random.nextInt(3) == 0) {
            this.useMove(Move.BLACK_FLASH, target);
        } else if (d > 5.0 && d < 24.0 && this.hasLineOfSight(target) && this.ready(Move.HOLLOW_PURPLE)) {
            this.getLookControl().setLookAt(target, 60.0F, 60.0F);
            this.useMove(Move.HOLLOW_PURPLE, target);
        }
    }

    /** Returns false when it lacks the energy or is on cooldown. A null target means "just show it off" or "hit whatever is hostile in front". */
    public boolean useMove(Move m, @Nullable LivingEntity target) {
        if (!(this.level() instanceof ServerLevel sl) || !this.ready(m)) {
            return false;
        }
        this.cursedEnergy -= m.cost;
        this.readyAt.put(m, this.tickCount + m.cooldownTicks);
        this.entityData.set(CAST_TICKS, 20);
        this.swing(InteractionHand.MAIN_HAND);

        switch (m) {
            case BLACK_FLASH -> {
                LivingEntity v = target;
                if (v == null || this.distanceTo(v) > 5.0) {
                    v = this.nearestHostile(sl, 5.0);
                }
                if (v != null && this.validVictim(v)) {
                    v.hurt(this.damageSources().mobAttack(this), 9.0F);
                    v.knockback(0.9, this.getX() - v.getX(), this.getZ() - v.getZ());
                    sl.sendParticles(ParticleTypes.ELECTRIC_SPARK, v.getX(), v.getY() + v.getBbHeight() / 2, v.getZ(), 30, 0.4, 0.5, 0.4, 0.2);
                    sl.sendParticles(ParticleTypes.SQUID_INK, v.getX(), v.getY() + v.getBbHeight() / 2, v.getZ(), 12, 0.3, 0.4, 0.3, 0.05);
                    this.markLoot(v.position());
                } else {
                    sl.sendParticles(ParticleTypes.ELECTRIC_SPARK, this.getX(), this.getEyeY(), this.getZ(), 20, 0.5, 0.3, 0.5, 0.15);
                }
                this.playSound(SoundEvents.LIGHTNING_BOLT_THUNDER, 0.4F, 2.0F);
            }
            case HOLLOW_PURPLE -> {
                Vec3 eye = this.getEyePosition();
                Vec3 dir = target != null ? target.getEyePosition().subtract(eye).normalize() : this.getLookAngle();
                Set<LivingEntity> hit = new HashSet<>();
                for (int i = 1; i <= 32 && hit.size() < 3; i++) {
                    Vec3 p = eye.add(dir.scale(i * 0.75));
                    BlockPos bp = BlockPos.containing(p);
                    if (!sl.getBlockState(bp).getCollisionShape(sl, bp).isEmpty()) {
                        break;
                    }
                    sl.sendParticles(ParticleTypes.WITCH, p.x, p.y, p.z, 2, 0.05, 0.05, 0.05, 0.0);
                    sl.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 1, 0.1, 0.1, 0.1, 0.0);
                    AABB box = new AABB(p.x - 0.8, p.y - 0.8, p.z - 0.8, p.x + 0.8, p.y + 0.8, p.z + 0.8);
                    for (LivingEntity v : sl.getEntitiesOfClass(LivingEntity.class, box, this::validVictim)) {
                        if (hit.add(v)) {
                            v.hurt(this.damageSources().indirectMagic(this, this), 14.0F);
                            this.markLoot(v.position());
                        }
                    }
                }
                this.playSound(SoundEvents.ENDER_DRAGON_SHOOT, 0.6F, 1.5F);
            }
            case REVERSE -> {
                this.heal(12.0F);
                sl.sendParticles(ParticleTypes.HEART, this.getX(), this.getY() + 1.2, this.getZ(), 8, 0.4, 0.5, 0.4, 0.02);
                this.playSound(SoundEvents.BEACON_POWER_SELECT, 0.8F, 1.6F);
            }
            case DOMAIN -> {
                this.domainTicks = 160;
                this.domainCenter = this.blockPosition();
                this.playSound(SoundEvents.BEACON_ACTIVATE, 1.0F, 0.6F);
            }
        }
        return true;
    }

    @Nullable
    private LivingEntity nearestHostile(ServerLevel sl, double radius) {
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, this.getBoundingBox().inflate(radius), this::validVictim)) {
            double d = this.distanceToSqr(e);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    private void tickDomain(ServerLevel sl) {
        this.domainTicks--;
        if (this.domainCenter == null) {
            this.domainTicks = 0;
            return;
        }
        double cx = this.domainCenter.getX() + 0.5;
        double cy = this.domainCenter.getY();
        double cz = this.domainCenter.getZ() + 0.5;
        if (this.domainTicks % 10 == 0) {
            for (int i = 0; i < 24; i++) {
                double a = i * (Math.PI * 2.0 / 24.0);
                sl.sendParticles(ParticleTypes.SQUID_INK, cx + Math.cos(a) * 8.0, cy + 0.3, cz + Math.sin(a) * 8.0, 1, 0, 0, 0, 0);
            }
            AABB box = new AABB(cx - 8, cy - 3, cz - 8, cx + 8, cy + 5, cz + 8);
            for (Monster m : sl.getEntitiesOfClass(Monster.class, box)) {
                m.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 2));
                m.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 30, 1));
                if (this.domainTicks % 20 == 0) {
                    m.hurt(this.damageSources().indirectMagic(this, this), 2.0F);
                }
            }
        }
    }

    // ------------------------------------------------------------------ interaction, death

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (this.level().isClientSide || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        if (sp.isShiftKeyDown() && Brain.isOp(sp)) {
            this.openInventory(sp);
            return InteractionResult.CONSUME;
        }
        ItemStack held = sp.getItemInHand(hand);
        if (!held.isEmpty() && PaqganeConfig.SENTIENT_ACCEPT_GIFTS.get()) {
            ItemStack one = held.split(1);
            ItemStack rest = this.inventory.addItem(one);
            if (!rest.isEmpty()) {
                held.grow(rest.getCount());
                PaqganeManager.whisper(sp, "my inventory is full bro");
            } else if (this.tickCount - this.lastThanksTick > 200) {
                this.lastThanksTick = this.tickCount;
                PaqganeManager.say(this.getServer(), "thx bro");
            }
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    public void openInventory(ServerPlayer op) {
        op.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new ChestMenu(MenuType.GENERIC_9x4, id, inv, this.inventory, 4),
                Component.literal("Paqgane's inventory")));
    }

    @Override
    public void die(DamageSource source) {
        if (!this.level().isClientSide) {
            if (PaqganeConfig.SENTIENT_KEEP_INVENTORY.get()) {
                this.saveBank();
            } else {
                Containers.dropContents(this.level(), this.blockPosition(), this.inventory);
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    ItemStack s = this.getItemBySlot(slot);
                    if (!s.isEmpty()) {
                        this.spawnAtLocation(s.copy());
                        this.setItemSlot(slot, ItemStack.EMPTY);
                    }
                }
                this.inventory.clearContent();
                this.saveBank();
            }
            SentientManager.onDeath(this, source);
        }
        super.die(source);
    }

    @Override
    protected void dropCustomDeathLoot(DamageSource source, int looting, boolean recentlyHit) {
        // items are handled in die(): kept in the bank or dropped there
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    // ------------------------------------------------------------------ animation

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<SentientEntity>(this, "main", 3, state -> {
            SentientEntity e = state.getAnimatable();
            if (e.castTicks() > 0) {
                return state.setAndContinue(CAST);
            }
            if (e.swinging) {
                return state.setAndContinue(ATTACK);
            }
            if (state.isMoving()) {
                return state.setAndContinue(WALK);
            }
            return state.setAndContinue(IDLE);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    // ------------------------------------------------------------------ goals

    private static class IntentGoal extends Goal {
        private final SentientEntity e;

        IntentGoal(SentientEntity e) {
            this.e = e;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return e.intent == Intent.FOLLOW || e.intent == Intent.GOTO || e.intent == Intent.MINE;
        }

        @Override
        public boolean canContinueToUse() {
            return this.canUse();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            e.intentTick();
        }
    }

    private static class WanderGoal extends WaterAvoidingRandomStrollGoal {
        private final SentientEntity e;

        WanderGoal(SentientEntity e) {
            super(e, 0.9D);
            this.e = e;
        }

        @Override
        public boolean canUse() {
            return e.intent == Intent.WANDER && super.canUse();
        }
    }
}
