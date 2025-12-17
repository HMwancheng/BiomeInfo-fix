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

    // --- 核心状态变量 ---
    // [逻辑] 当前已经确认所在的群系 (用于判断是否发生了变化)
    private ResourceKey<Biome> currentConfirmedKey = null;
    
    // [视觉] 当前屏幕上正在渲染的群系 (用于绘制文字)
    private ResourceKey<Biome> renderingKey = null;

    // [防抖] 临时检测到的群系
    private ResourceKey<Biome> pendingKey = null;
    private int pendingTime = 0;
    
    // 历史记录
    private final Deque<ResourceKey<Biome>> biomeHistory = new ArrayDeque<>();
    
    // 动画状态
    private int displayTime = 0;
    private int alpha = 0;
    private boolean fadingIn = false;

    // 维度显示逻辑
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

            // 1. 获取当前数据
            BlockPos pos = client.getCameraEntity().blockPosition();
            if (!client.level.isLoaded(pos)) return;
            
            Holder<Biome> biomeHolder = client.level.getBiome(pos);
            if (!biomeHolder.isBound()) return;
            
            Optional<ResourceKey<Biome>> optionalKey = biomeHolder.unwrapKey();
            if (optionalKey.isEmpty()) return;
            ResourceKey<Biome> actualCurrentKey = optionalKey.get();
            ResourceKey<Level> currentDimension = client.level.dimension();

            // 2. 防抖逻辑
            if (!Objects.equals(pendingKey, actualCurrentKey)) {
                pendingKey = actualCurrentKey;
                pendingTime = 0;
            } else {
                pendingTime++;
            }

            // 3. 确认显示逻辑 (Trigger Logic)
            // 只有当群系发生变化 (相对于 currentConfirmedKey) 且停留时间足够时触发
            if (pendingTime >= config.delayTicks && !Objects.equals(currentConfirmedKey, pendingKey)) {
                
                // 立即更新逻辑位置，防止下一次 Tick 重复进入此代码块
                currentConfirmedKey = pendingKey;

                // 判断是否切换了维度 (修复问题2：维度横跳不显示)
                boolean dimChanged = !Objects.equals(currentDimension, lastDisplayedDimension);

                // 判断是否在历史记录中
                boolean inHistory = biomeHistory.contains(pendingKey);

                // 更新历史记录
                // 即使在历史中也要重新加入(移到队头)，保证它是“最近”的
                if (inHistory) {
                    biomeHistory.remove(pendingKey); 
                }
                biomeHistory.addFirst(pendingKey);
                if (biomeHistory.size() > config.historySize) {
                    biomeHistory.removeLast();
                }

                // 核心决策：显示还是静默？
                // 如果是 (不在历史中) 或者 (维度发生了变化)，则强制显示
                if (!inHistory || dimChanged) {
                    // --- 触发新显示 ---
                    renderingKey = pendingKey; // 更新视觉文字
                    
                    lastDisplayedDimension = currentDimension;

                    // 维度显示开关逻辑
                    if (config.showDimension) {
                        if (config.dimensionShowOnWorldChangeOnly) {
                            isDimensionVisibleForCurrentBiome = dimChanged;
                        } else {
                            isDimensionVisibleForCurrentBiome = true;
                        }
                    } else {
                        isDimensionVisibleForCurrentBiome = false;
                    }

                    // 重置动画
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
                    // --- 历史记录中：静默处理 ---
                    // 修复问题1：直接消失无动画
                    // 我们不更新 renderingKey，所以屏幕上还是旧的标题(如果有的话)
                    // 我们只操作动画状态，让旧标题加速淡出
                    
                    fadingIn = false;
                    displayTime = 0; // 强制结束停留时间，开始 Alpha 衰减
                    
                    // 注意：此时 renderingKey 仍然是上一个群系(C)
                    // 屏幕上会显示 C 慢慢消失，而不是瞬间变成 B 然后消失，也不是瞬间消失
                }
            }
            
            // 累加生命周期 (用于维度延迟)
            if (alpha > 0) {
                currentBiomeLifeTime++;
            }

            // 4. 动画状态机 (Alpha 衰减/增加)
            if (!fadingIn) {
                // 淡出阶段
                if (!config.fadeOut && alpha != 255)
                    alpha = 255;
                else if (config.fadeOut) {
                    if (displayTime > 0)
                        displayTime--; // 停留期
                    else if (alpha > 0)
                        alpha -= 10;   // 衰减期
                }
            } else { 
                // 淡入阶段
                alpha += 10;
                if (alpha >= 255) {
                    fadingIn = false;
                    displayTime = Math.max(0, config.displayTime);
                    alpha = 255;
                }
            }
            
            // 动画完全结束后，清理 renderingKey，避免残留引用
            if (alpha <= 0) {
                alpha = 0;
                // 可选：renderingKey = null; 
                // 但保留着也没事，反正 alpha 是 0 不会画出来
            }
        });

        // --- 渲染逻辑 ---
        // 注意：这里全部使用 renderingKey 而不是 currentConfirmedKey
        HudElementRegistry.attachElementAfter(VanillaHudElements.TITLE_AND_SUBTITLE, ResourceLocation.fromNamespaceAndPath("biomeinfo", "overlay"), (graphics, delta) -> {
            if (config.enabled && renderingKey != null && alpha > 0) {
                Minecraft mc = Minecraft.getInstance();
                if (hideBecauseOfF1(mc) || hideBecauseOfF3(mc)) return;

                Component biomeName = getBiomeName(renderingKey);
                
                // 维度文字
                Component dimName = null;
                int dimAlpha = 0;

                if (isDimensionVisibleForCurrentBiome && mc.level != null) {
                    if (currentBiomeLifeTime < config.dimensionDelayTicks) {
                        dimAlpha = 0;
                    } else {
                        int fadeProgress = (currentBiomeLifeTime - config.dimensionDelayTicks) * 25;
                        dimAlpha = Math.min(alpha, Math.min(255, fadeProgress));
                        
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

                // 绘制副标题
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

                    graphics.drawString(mc.font, dimName, -dimOffset, (int)yOffset, config.dimensionColor | (dimAlpha << 24), config.textShadow);
                    
                    pose.popMatrix();
                }

                pose.popMatrix();
            }
        });
        
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(this);
    }

    // --- 辅助方法 (保持不变) ---
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
