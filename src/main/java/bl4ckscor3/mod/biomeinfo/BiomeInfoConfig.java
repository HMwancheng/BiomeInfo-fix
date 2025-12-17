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

    // 【修复】添加 @Color 注解，恢复色盘选择器
    @ConfigEntry.Color 
    public int color = 0xFFFFFF;

    public boolean fadeIn = true;
    public boolean fadeOut = true;
    public int displayTime = 40;
    public boolean hideWithUI = true;
    public boolean hideOnDebugScreen = true;
    public boolean fallbackOnUntranslatableName = true;
    public boolean appendModName = true;
    
    // 删除 @CollapsibleObject，Cloth Config 会自动处理枚举
    @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.BUTTON)
    public PositionPreset positionPreset = PositionPreset.TOP_LEFT;

    @ConfigEntry.Gui.Tooltip(count = 2)
    public int delayTicks = 10;

    @ConfigEntry.Gui.Tooltip(count = 2)
    public int historySize = 3;

    // --- 维度显示配置 ---

    @ConfigEntry.Gui.Tooltip
    public boolean showDimension = true;

    @ConfigEntry.Gui.Tooltip(count = 3)
    public boolean dimensionShowOnWorldChangeOnly = false;

    public int dimensionDelayTicks = 20;

    public boolean dimensionBelow = true;
    public double dimensionScale = 0.8; 
    public int dimensionYOffset = 2;

    // 【修复】添加 @Color 注解
    @ConfigEntry.Color
    public int dimensionColor = 0xFFFFFF; 
    
    public TextAlignment textAlignment = TextAlignment.LEFT; 
}
