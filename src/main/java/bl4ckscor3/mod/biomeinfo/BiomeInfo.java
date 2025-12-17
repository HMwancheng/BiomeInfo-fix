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

// 1.21 必须的 Import
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;

public class BiomeInfo implements ClientModInitializer, IdentifiableResourceReloadListener {
    public static final int MARGIN = 3;
    static BiomeInfoConfig config;

    // --- 状态机变量 ---
    private ResourceKey<Biome> currentDisplayedKey = null; // 当前正在显示的群系
    private ResourceKey<Biome> pendingKey = null;          // 当前玩家所处，但还未确认显示的群系
    private int pendingTime = 0;                           // 防抖计数器 (Ticks)
    
    // 历史记录 (Memory)
    private final Deque<ResourceKey<Biome>> biomeHistory = new ArrayDeque<>();
    
    // 动画状态
    private int displayTime = 0;
    private int alpha = 0;
    private boolean fadingIn = false;

    // --- 维度显示逻辑状态 ---
    private ResourceKey<Level> lastDisplayedDimension = null;  // 上一次显示时的维度
    private boolean isDimensionVisibleForCurrentBiome = false; // 当前这个标题是否允许显示维度
    private int currentBiomeLifeTime = 0;                      // 当前标题存活时间 (用于维度延迟)

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

        // --- Client Tick 逻辑：处理核心状态机 ---
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;

            // 1. 获取玩家当前的真实数据
            BlockPos pos = client.getCameraEntity().blockPosition();
            if (!client.level.isLoaded(pos)) return;
            
            Holder<Biome> biomeHolder = client.level.getBiome(pos);
            if (!biomeHolder.isBound()) return;
            
            Optional<ResourceKey<Biome>> optionalKey = biomeHolder.unwrapKey();
            if (optionalKey.isEmpty()) return;
            ResourceKey<Biome> actualCurrentKey = optionalKey.get();
            ResourceKey<Level> currentDimension = client.level.dimension();

            // 2. 防抖逻辑 (Delay Logic)
            if (!Objects.equals(pendingKey, actualCurrentKey)) {
                // 如果玩家位置变了，重置计数器
                pendingKey = actualCurrentKey;
                pendingTime = 0;
            } else {
                // 如果位置没变，累加停留时间
                pendingTime++;
            }

            // 3. 确认显示逻辑 (Trigger Logic)
            // 条件：停留时间达标 且 这个群系不是当前正在显示的那个
            if (pendingTime >= config.delayTicks && !Objects.equals(currentDisplayedKey, pendingKey)) {
                
                // 4. 历史记录检查 (Memory Logic)
                if (!biomeHistory.contains(pendingKey)) {
                    // --- 这是一个新群系，准备显示 ---
                    currentDisplayedKey = pendingKey;
                    
                    // 加入历史记录
                    biomeHistory.addFirst(pendingKey);
                    if (biomeHistory.size() > config.historySize) {
                        biomeHistory.removeLast();
                    }

                    // --- 判断维度是否需要显示 ---
                    boolean dimChanged = !Objects.equals(currentDimension, lastDisplayedDimension);
                    lastDisplayedDimension = currentDimension;

                    if (config.showDimension) {
                        if (config.dimensionShowOnWorldChangeOnly) {
                            // 配置：只在世界切换时显示
                            isDimensionVisibleForCurrentBiome = dimChanged;
                        } else {
                            // 配置：总是显示
                            isDimensionVisibleForCurrentBiome = true;
                        }
                    } else {
                        isDimensionVisibleForCurrentBiome = false;
                    }

                    // 重置生命周期 (用于维度的N秒延迟)
                    currentBiomeLifeTime = 0;

                    // 触发淡入动画
                    if (config.fadeIn) {
                        displayTime = 0;
                        alpha = 0;
                        fadingIn = true;
                    } else {
                        displayTime = Math.max(0, config.displayTime);
                        alpha = 255;
                    }
                } else {
                    // --- 这个群系在历史记录里 ---
                    // 静默切换：内部状态变了，但屏幕不弹窗，不重置 alpha
                    currentDisplayedKey = pendingKey;
                    // 也不更新维度的可见性状态，保持上一个的状态
                }
            }
            
            // 累加当前标题的存活时间 (只要在显示)
            if (alpha > 0) {
                currentBiomeLifeTime++;
            }

            // 5. 动画处理
            if (!fadingIn) {
                if (!config.fadeOut && alpha != 255)
                    alpha = 255;
                else if (config.fadeOut) {
                    if (displayTime > 0)
                        displayTime--;
                    else if (alpha > 0)
                        alpha -= 10;
                }
            } else { // 正在淡入
                alpha += 10;
                if (alpha >= 255) {
                    fadingIn = false;
                    displayTime = Math.max(0, config.displayTime);
                    alpha = 255;
                }
            }
        });

        // --- 渲染逻辑 (HUD) ---
        HudElementRegistry.attachElementAfter(VanillaHudElements.TITLE_AND_SUBTITLE, ResourceLocation.fromNamespaceAndPath("biomeinfo", "overlay"), (graphics, delta) -> {
            if (config.enabled && currentDisplayedKey != null && alpha > 0) {
                Minecraft mc = Minecraft.getInstance();
                if (hideBecauseOfF1(mc) || hideBecauseOfF3(mc)) return;

                // 准备主标题
                Component biomeName = getBiomeName(currentDisplayedKey);
                
                // 准备副标题 (维度)
                Component dimName = null;
                // 判断：总开关 && 当前标题允许显示维度 && 达到配置的延迟时间
                if (isDimensionVisibleForCurrentBiome && 
                    currentBiomeLifeTime >= config.dimensionDelayTicks && 
                    mc.level != null) {
                    
                    dimName = getDimensionName(mc.level.dimension().location());
                }

                // 读取配置
                float scale = (float) config.scale;
                float dimScale = (float) config.dimensionScale;
                PositionPreset positionPreset = config.positionPreset;
                Matrix3x2fStack pose = graphics.pose();
                Window window = mc.getWindow();

                pose.pushMatrix();

                // 1. 移动到基准点 (屏幕中心或预设位置)
                float renderX = positionPreset.posX(window);
                float renderY = positionPreset.posY(window, mc.font);
                pose.translate(renderX, renderY); // 修复了 1.21 矩阵参数错误
                
                // 2. 绘制主标题 (群系名)
                pose.pushMatrix();
                pose.scale(scale, scale);
                // 修复居中：使用负偏移量
                int textOffset = positionPreset.textAlignment().getNegativeOffset(mc.font, biomeName);
                graphics.drawString(mc.font, biomeName, -textOffset, 0, config.color | (alpha << 24), config.textShadow);
                pose.popMatrix(); // 结束主标题缩放

                // 3. 绘制副标题 (维度名)
                if (dimName != null) {
                    pose.pushMatrix();
                    pose.scale(dimScale, dimScale);
                    int dimOffset = positionPreset.textAlignment().getNegativeOffset(mc.font, dimName);
                    
                    // 计算 Y 轴偏移
                    float yOffset;
                    if (config.dimensionBelow) {
                        // 在下方：主标题高度 * 主缩放 + 间距，然后除以 dimScale 以适应当前缩放坐标系
                        yOffset = (mc.font.lineHeight * scale + config.dimensionYOffset) / dimScale;
                    } else {
                        // 在上方：负方向偏移
                        yOffset = -(mc.font.lineHeight + config.dimensionYOffset / dimScale); 
                    }

                    // 绘制维度名 (Alpha 跟随主标题)
                    graphics.drawString(mc.font, dimName, -dimOffset, (int)yOffset, config.dimensionColor | (alpha << 24), config.textShadow);
                    
                    pose.popMatrix(); // 结束维度缩放
                }

                pose.popMatrix(); // 结束总位移
            }
        });
        
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(this);
    }

    // --- 辅助方法 ---

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
        // 如果没有翻译 Key，就用英文格式化
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

    // 1.21 兼容的 reload 方法 (4个参数)
    @Override
    public CompletableFuture<Void> reload(PreparableReloadListener.PreparationBarrier preparationBarrier, ResourceManager resourceManager, Executor backgroundExecutor, Executor gameExecutor) {
        return CompletableFuture.runAsync(NAME_CACHE::clear, gameExecutor).thenCompose(preparationBarrier::wait);
    }

    @Override
    public ResourceLocation getFabricId() {
        return ResourceLocation.fromNamespaceAndPath("biomeinfo", "cache_invalidation");
    }
}
