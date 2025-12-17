package bl4ckscor3.mod.biomeinfo;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.JanksonConfigSerializer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.apache.commons.lang3.StringUtils;
import org.joml.Matrix3x2fStack;

import com.mojang.blaze3d.platform.Window;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;

public class BiomeInfo implements ClientModInitializer, IdentifiableResourceReloadListener {
    public static final int MARGIN = 3;
    static BiomeInfoConfig config;

    private ResourceKey<Biome> currentDisplayedKey = null;
    private ResourceKey<Biome> pendingKey = null;
    private int pendingTime = 0;
    
    private final Deque<ResourceKey<Biome>> biomeHistory = new ArrayDeque<>();
    
    private int displayTime = 0;
    private int alpha = 0;
    private boolean fadingIn = false;

    private ResourceKey<Level> lastDisplayedDimension = null;
    private boolean isDimensionVisibleForCurrentBiome = false;
    private int currentBiomeLifeTime = 0;

    public static final Map<ResourceKey<Biome>, Component> NAME_CACHE = new HashMap<>();

    @Override
    public void onInitializeClient() {
        AutoConfig.register(BiomeInfoConfig.class, JanksonConfigSerializer::new);
        ConfigHolder<BiomeInfoConfig> configHolder = AutoConfig.getConfigHolder(BiomeInfoConfig.class);
        configHolder.registerSaveListener((holder, config) -> {
            NAME_CACHE.clear();
            return InteractionResult.SUCCESS;
        });
        config = configHolder.getConfig();

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;

            BlockPos pos = client.getCameraEntity().blockPosition();
            if (!client.level.isLoaded(pos)) return;
            
            Holder<Biome> biomeHolder = client.level.getBiome(pos);
            if (!biomeHolder.isBound()) return;
            
            Optional<ResourceKey<Biome>> optionalKey = biomeHolder.unwrapKey();
            if (optionalKey.isEmpty()) return;
            ResourceKey<Biome> actualCurrentKey = optionalKey.get();
            ResourceKey<Level> currentDimension = client.level.dimension();

            if (!Objects.equals(pendingKey, actualCurrentKey)) {
                pendingKey = actualCurrentKey;
                pendingTime = 0;
            } else {
                pendingTime++;
            }

            if (pendingTime >= config.delayTicks && !Objects.equals(currentDisplayedKey, pendingKey)) {
                
                if (!biomeHistory.contains(pendingKey)) {
                    // --- 新群系：正常显示 ---
                    currentDisplayedKey = pendingKey;
                    
                    biomeHistory.addFirst(pendingKey);
                    if (biomeHistory.size() > config.historySize) {
                        biomeHistory.removeLast();
                    }

                    boolean dimChanged = !Objects.equals(currentDimension, lastDisplayedDimension);
                    lastDisplayedDimension = currentDimension;

                    if (config.showDimension) {
                        if (config.dimensionShowOnWorldChangeOnly) {
                            isDimensionVisibleForCurrentBiome = dimChanged;
                        } else {
                            isDimensionVisibleForCurrentBiome = true;
                        }
                    } else {
                        isDimensionVisibleForCurrentBiome = false;
                    }

                    currentBiomeLifeTime = 0;

                    if (config.fadeIn) {
                        displayTime = 0;
                        alpha = 0;
                        fadingIn = true;
                    } else {
                        displayTime = Math.max(0, config.displayTime);
                        alpha = 255;
                    }
                } else {
                    // --- 历史群系：强制隐藏 ---
                    // 【修复】之前这里只是切换了 Key，导致渲染器用旧的 Time/Alpha 渲染了新的 Key
                    // 现在强制将 Alpha 设为 0，相当于立刻关闭 HUD
                    currentDisplayedKey = pendingKey;
                    alpha = 0; 
                    fadingIn = false;
                    displayTime = 0;
                    currentBiomeLifeTime = 0;
                }
            }
            
            if (alpha > 0) {
                currentBiomeLifeTime++;
            }

            if (!fadingIn) {
                if (!config.fadeOut && alpha != 255)
                    alpha = 255;
                else if (config.fadeOut) {
                    if (displayTime > 0)
                        displayTime--;
                    else if (alpha > 0)
                        alpha -= 10;
                }
            } else {
                alpha += 10;
                if (alpha >= 255) {
                    fadingIn = false;
                    displayTime = Math.max(0, config.displayTime);
                    alpha = 255;
                }
            }
        });

        HudElementRegistry.attachElementAfter(VanillaHudElements.TITLE_AND_SUBTITLE, ResourceLocation.fromNamespaceAndPath("biomeinfo", "overlay"), (graphics, delta) -> {
            if (config.enabled && currentDisplayedKey != null && alpha > 0) {
                Minecraft mc = Minecraft.getInstance();
                if (hideBecauseOfF1(mc) || hideBecauseOfF3(mc)) return;

                Component biomeName = getBiomeName(currentDisplayedKey);
                
                // 维度文字准备
                Component dimName = null;
                // 【修复】计算维度文字的 Alpha
                int dimAlpha = 0;

                if (isDimensionVisibleForCurrentBiome && mc.level != null) {
                    // 如果生命周期还没到延迟时间，Alpha = 0
                    if (currentBiomeLifeTime < config.dimensionDelayTicks) {
                        dimAlpha = 0;
                    } else {
                        // 到了时间，计算淡入
                        // 这里使用 25 的步进，大约 0.5 秒淡入完成 (10 ticks)
                        int fadeProgress = (currentBiomeLifeTime - config.dimensionDelayTicks) * 25;
                        // 维度 Alpha 不能超过主标题 Alpha (防止主标题淡出时维度还亮着)
                        dimAlpha = Math.min(alpha, Math.min(255, fadeProgress));
                        
                        // 只有当 dimAlpha > 0 时才去获取名字，节省性能
                        if (dimAlpha > 0) {
                            dimName = getDimensionName(mc.level.dimension().location());
                        }
                    }
                }

                float scale = (float) config.scale;
                float dimScale = (float) config.dimensionScale;
                PositionPreset positionPreset = config.positionPreset;
                Matrix3x2fStack pose = graphics.pose();
                Window window = mc.getWindow();

                pose.pushMatrix();

                float renderX = positionPreset.posX(window);
                float renderY = positionPreset.posY(window, mc.font);
                pose.translate(renderX, renderY);
                
                // 绘制主标题
                pose.pushMatrix();
                pose.scale(scale, scale);
                int textOffset = positionPreset.textAlignment().getNegativeOffset(mc.font, biomeName);
                graphics.drawString(mc.font, biomeName, -textOffset, 0, config.color | (alpha << 24), config.textShadow);
                pose.popMatrix();

                // 绘制副标题 (维度)
                if (dimName != null && dimAlpha > 0) {
                    pose.pushMatrix();
                    pose.scale(dimScale, dimScale);
                    int dimOffset = positionPreset.textAlignment().getNegativeOffset(mc.font, dimName);
                    
                    float yOffset;
                    if (config.dimensionBelow) {
                        yOffset = (mc.font.lineHeight * scale + config.dimensionYOffset) / dimScale;
                    } else {
                        yOffset = -(mc.font.lineHeight + config.dimensionYOffset / dimScale); 
                    }

                    // 【修复】使用 dimAlpha 而不是 alpha
                    graphics.drawString(mc.font, dimName, -dimOffset, (int)yOffset, config.dimensionColor | (dimAlpha << 24), config.textShadow);
                    
                    pose.popMatrix();
                }

                pose.popMatrix();
            }
        });
        
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(this);
    }
    
    // ... (辅助方法保持不变，省略) ...
    private static Component getBiomeName(ResourceKey<Biome> key) {
        return NAME_CACHE.computeIfAbsent(key, k -> {
            ResourceLocation location = key.location();
            String translationKey = Util.makeDescriptionId("biome", location);
            MutableComponent biomeName = Component.translatable(translationKey);
            MutableComponent displayName = biomeName;
            if (config.fallbackOnUntranslatableName) {
                if (biomeName.getString().equals(translationKey)) {
                    displayName = Component.literal(snakeCaseToEnglish(key.location().getPath()));
                }
            }
            if (config.appendModName) {
                String modName = getModName(location);
                if (modName != null)
                    displayName = displayName.append(Component.literal(String.format(" (%s)", modName)));
            }
            return displayName;
        });
    }

    private static Component getDimensionName(ResourceLocation dimLoc) {
        String translationKey = Util.makeDescriptionId("dimension", dimLoc);
        MutableComponent dimName = Component.translatable(translationKey);
        if (dimName.getString().equals(translationKey)) {
             return Component.literal(snakeCaseToEnglish(dimLoc.getPath()));
        }
        return dimName;
    }

    private static boolean hideBecauseOfF1(Minecraft mc) {
        return mc.options.hideGui && config.hideWithUI;
    }

    private static boolean hideBecauseOfF3(Minecraft mc) {
        return mc.getDebugOverlay().showDebugScreen() && config.hideOnDebugScreen;
    }

    private static String snakeCaseToEnglish(String biomePath) {
        String[] words = biomePath.split("_");
        StringBuilder formatted = new StringBuilder();
        for (String word : words) {
            formatted.append(StringUtils.capitalize(word)).append(" ");
        }
        return formatted.toString().trim();
    }

    private static String getModName(ResourceLocation location) {
        String namespace = location.getNamespace();
        return FabricLoader.getInstance().getAllMods()
                .stream()
                .map(ModContainer::getMetadata)
                .filter(meta -> meta.getId().equals(namespace))
                .findFirst()
                .map(ModMetadata::getName)
                .orElseGet(() -> snakeCaseToEnglish(namespace));
    }

    @Override
    public CompletableFuture<Void> reload(PreparableReloadListener.PreparationBarrier preparationBarrier, ResourceManager resourceManager, Executor backgroundExecutor, Executor gameExecutor) {
        return CompletableFuture.runAsync(NAME_CACHE::clear, gameExecutor).thenCompose(preparationBarrier::wait);
    }

    @Override
    public ResourceLocation getFabricId() {
        return ResourceLocation.fromNamespaceAndPath("biomeinfo", "cache_invalidation");
    }
}
