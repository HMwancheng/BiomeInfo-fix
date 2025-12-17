package bl4ckscor3.mod.biomeinfo;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

@Config(name = "biomeinfo")
public class BiomeInfoConfig implements ConfigData {
    public boolean enabled = true;
    public int posX = 10;
    public int posY = 10;
    public double scale = 1.0;
    public boolean textShadow = true;
    public int color = 0xFFFFFF;
    public boolean fadeIn = true;
    public boolean fadeOut = true;
    public int displayTime = 40;
    public boolean hideWithUI = true;
    public boolean hideOnDebugScreen = true;
    public boolean fallbackOnUntranslatableName = true;
    public boolean appendModName = true;
    
    // 【修复点】删除了 @ConfigEntry.Gui.CollapsibleObject
    // Cloth Config 会自动为 Enum 生成循环切换按钮，不需要额外注解
    @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.BUTTON) // 可选：指定显示样式为按钮
    public PositionPreset positionPreset = PositionPreset.TOP_LEFT;

    // --- 新增功能：基础配置 ---
    
    @ConfigEntry.Gui.Tooltip(count = 2)
    public int delayTicks = 10;

    @ConfigEntry.Gui.Tooltip(count = 2)
    public int historySize = 3;

    // --- 新增功能：维度显示配置 ---

    @ConfigEntry.Gui.Tooltip
    public boolean showDimension = true;

    @ConfigEntry.Gui.Tooltip(count = 3)
    public boolean dimensionShowOnWorldChangeOnly = false;

    public int dimensionDelayTicks = 20;

    public boolean dimensionBelow = true;
    public double dimensionScale = 0.8; 
    public int dimensionYOffset = 2;
    public int dimensionColor = 0xFFFFFF;
    
    // 兼容字段，可以保留
    public TextAlignment textAlignment = TextAlignment.LEFT; 
}
