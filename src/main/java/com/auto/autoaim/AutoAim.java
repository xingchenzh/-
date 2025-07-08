package com.auto.autoaim;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.KeyMapping;
import net.minecraft.util.Mth;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

@Mod(AutoAim.MODID)
public class AutoAim {
    public static final String MODID = "autoaim";
    private static final KeyMapping AIM_KEY = new KeyMapping(
            "key.autoaim.aim",
            GLFW.GLFW_KEY_X,
            "category.autoaim.keys"
    );

    private boolean toggleState = false;
    private boolean keyWasPressed = false;
    private LivingEntity currentTarget = null;
    private static ForgeConfigSpec CONFIG_SPEC;
    private static Config CONFIG;

    public AutoAim() {
        MinecraftForge.EVENT_BUS.register(this);
        // 1.18.2 客户端初始化事件
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientSetup);

        var pair = new ForgeConfigSpec.Builder().configure(Config::new);
        CONFIG_SPEC = pair.getRight();
        CONFIG = pair.getLeft();
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CONFIG_SPEC);
    }

    // 1.18.2 客户端初始化
    private void clientSetup(FMLClientSetupEvent event) {
        ClientRegistry.registerKeyBinding(AIM_KEY);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        boolean keyPressed = AIM_KEY.isDown();
        boolean shouldAim = false;

        if (keyPressed != keyWasPressed) {
            System.out.println("按键状态变化: " + keyPressed);
        }

        switch (CONFIG.triggerMode.get()) {
            case HOLD -> shouldAim = keyPressed;
            case TOGGLE -> {
                if (keyPressed && !keyWasPressed) {
                    toggleState = !toggleState;
                }
                shouldAim = toggleState;
            }
        }
        keyWasPressed = keyPressed;

        if (shouldAim) {
            Optional<LivingEntity> target = findNearestEntity(player);
            target.ifPresentOrElse(
                    entity -> {
                        currentTarget = entity;
                        lookAtPosition(player, entity.getEyePosition());
                    },
                    () -> {
                        currentTarget = null;
                    }
            );
        } else {
            currentTarget = null;
        }
    }

    private Optional<LivingEntity> findNearestEntity(LocalPlayer player) {
        Vec3 center = player.getEyePosition();
        double radius = CONFIG.searchRadius.get();
        AABB area = new AABB(
                center.x - radius, center.y - radius, center.z - radius,
                center.x + radius, center.y + radius, center.z + radius
        );

        // 1.18.2 获取实体方式
        return player.level.getEntitiesOfClass(
                        LivingEntity.class,
                        area,
                        e -> isValidTarget(e, player)
                ).stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(player)));
    }

    private boolean isValidTarget(LivingEntity entity, LocalPlayer player) {
        // 基础过滤
        if (entity == player || !entity.isAlive() || entity.isInvisible()) {
            return false;
        }

        // 视线检测 (1.18.2 ClipContext 用法)
        if (CONFIG.obstacleCheck.get()) {
            Vec3 start = player.getEyePosition();
            Vec3 end = entity.getEyePosition();
            // 1.18.2 创建 ClipContext
            ClipContext context = new ClipContext(
                    start, end,
                    ClipContext.Block.COLLIDER,  // 1.18.2 使用 COLLIDER
                    ClipContext.Fluid.ANY,       // 1.18.2 流体检测选项
                    player
            );
            BlockHitResult result = player.level.clip(co