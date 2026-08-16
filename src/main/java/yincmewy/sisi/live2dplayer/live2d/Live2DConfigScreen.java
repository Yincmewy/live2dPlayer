package yincmewy.sisi.live2dplayer.live2d;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class Live2DConfigScreen {
    private Live2DConfigScreen() {
    }

    public static Screen create(Screen parent) {
        Live2DRuntime runtime = Live2DRuntime.INSTANCE;
        runtime.init();
        Live2DConfig config = runtime.config();
        Live2DModel selectedModel = runtime.selectedModel();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("Live2D 播放器设置"))
                .setSavingRunnable(() -> {
                    runtime.saveConfig();
                    runtime.refreshModels();
                })
                .setAlwaysShowTabs(true);
        ConfigEntryBuilder entries = builder.entryBuilder();

        ConfigCategory general = builder.getOrCreateCategory(Component.literal("常规"));
        general.addEntry(entries.startBooleanToggle(Component.literal("启用"), config.enabled)
                .setDefaultValue(false)
                .setSaveConsumer(value -> config.enabled = value)
                .setTooltip(Component.literal("在游戏 HUD 中显示 Live2D 模型。"))
                .build());
        general.addEntry(entries.startSelector(Component.literal("模型"),
                        selectorArray(runtime.modelNames(), config.selectedModel),
                        config.selectedModel)
                .setDefaultValue(config.selectedModel)
                .setSaveConsumer(value -> config.selectedModel = value == null ? "" : value)
                .setTooltip(Component.literal("模型文件夹位于 config/live2dplayer/model。"))
                .build());
        general.addEntry(entries.startSelector(Component.literal("动作"),
                        selectorArray(runtime.motionNames(selectedModel), config.selectedMotion),
                        config.selectedMotion)
                .setDefaultValue("Idle")
                .setSaveConsumer(value -> config.selectedMotion = value == null ? "Idle" : value)
                .build());
        general.addEntry(entries.startEnumSelector(Component.literal("动作模式"),
                        Live2DConfig.MotionMode.class,
                        config.motionMode == null ? Live2DConfig.MotionMode.AUTO : config.motionMode)
                .setDefaultValue(Live2DConfig.MotionMode.AUTO)
                .setEnumNameProvider(value -> Component.literal(motionModeName(value)))
                .setSaveConsumer(value -> config.motionMode = value)
                .build());
        general.addEntry(entries.startTextDescription(Component.literal(
                "模型：" + runtime.models().size()
                        + " | 动作：" + runtime.motionNames(selectedModel).size()
                        + " | 表情：" + runtime.expressionNames(selectedModel).size()
                        + "\n" + runtime.renderer().loadStatus())).build());

        ConfigCategory expressions = builder.getOrCreateCategory(Component.literal("表情"));
        List<String> expressionNames = runtime.expressionNames(selectedModel);
        if (expressionNames.isEmpty()) {
            expressions.addEntry(entries.startTextDescription(
                    Component.literal("当前模型没有可用的 .exp3.json 表情。")).build());
        } else {
            for (String expressionName : expressionNames) {
                expressions.addEntry(entries.startBooleanToggle(
                                Component.literal(expressionName),
                                runtime.isExpressionSelected(expressionName))
                        .setDefaultValue(false)
                        .setSaveConsumer(value -> {
                            if (value) {
                                if (!config.selectedExpressions.contains(expressionName)) {
                                    config.selectedExpressions.add(expressionName);
                                }
                            } else {
                                config.selectedExpressions.remove(expressionName);
                            }
                        })
                        .build());
            }
        }

        ConfigCategory transform = builder.getOrCreateCategory(Component.literal("位置与显示"));
        transform.addEntry(entries.startFloatField(Component.literal("X"), config.x)
                .setMin(-4096.0F).setMax(4096.0F)
                .setDefaultValue(18.0F)
                .setSaveConsumer(value -> config.x = value)
                .build());
        transform.addEntry(entries.startFloatField(Component.literal("Y"), config.y)
                .setMin(-4096.0F).setMax(4096.0F)
                .setDefaultValue(72.0F)
                .setSaveConsumer(value -> config.y = value)
                .build());
        transform.addEntry(entries.startFloatField(Component.literal("缩放"), config.scale)
                .setMin(0.03F).setMax(4.0F)
                .setDefaultValue(0.28F)
                .setSaveConsumer(value -> config.scale = value)
                .build());
        transform.addEntry(entries.startFloatField(Component.literal("不透明度"), config.alpha)
                .setMin(0.0F).setMax(1.0F)
                .setDefaultValue(1.0F)
                .setSaveConsumer(value -> config.alpha = value)
                .build());
        transform.addEntry(entries.startFloatField(Component.literal("鼠标强度"), config.mouseStrength)
                .setMin(0.0F).setMax(3.0F)
                .setDefaultValue(1.0F)
                .setSaveConsumer(value -> config.mouseStrength = value)
                .build());
        transform.addEntry(entries.startFloatField(Component.literal("呼吸速度"), config.idleSpeed)
                .setMin(0.0F).setMax(5.0F)
                .setDefaultValue(1.0F)
                .setSaveConsumer(value -> config.idleSpeed = value)
                .build());
        transform.addEntry(entries.startBooleanToggle(Component.literal("镜像"), config.mirror)
                .setDefaultValue(false)
                .setSaveConsumer(value -> config.mirror = value)
                .build());
        transform.addEntry(entries.startBooleanToggle(Component.literal("跟随鼠标"), config.followMouse)
                .setDefaultValue(false)
                .setSaveConsumer(value -> config.followMouse = value)
                .build());

        ConfigCategory animation = builder.getOrCreateCategory(Component.literal("动画"));
        animation.addEntry(entries.startFloatField(Component.literal("动作强度"), config.motionScale)
                .setMin(0.0F).setMax(3.0F)
                .setDefaultValue(1.0F)
                .setSaveConsumer(value -> config.motionScale = value)
                .build());
        animation.addEntry(entries.startBooleanToggle(Component.literal("物理"), config.physicsEnabled)
                .setDefaultValue(true)
                .setSaveConsumer(value -> config.physicsEnabled = value)
                .build());
        animation.addEntry(entries.startFloatField(Component.literal("物理强度"), config.physicsScale)
                .setMin(0.0F).setMax(5.0F)
                .setDefaultValue(1.0F)
                .setSaveConsumer(value -> config.physicsScale = value)
                .build());
        animation.addEntry(entries.startBooleanToggle(Component.literal("自动眨眼"), config.autoBlink)
                .setDefaultValue(true)
                .setSaveConsumer(value -> config.autoBlink = value)
                .build());
        animation.addEntry(entries.startBooleanToggle(Component.literal("移动口型"), config.lipSync)
                .setDefaultValue(false)
                .setSaveConsumer(value -> config.lipSync = value)
                .setTooltip(Component.literal("移动或滞空时自动驱动口型参数。"))
                .build());

        ConfigCategory performance = builder.getOrCreateCategory(Component.literal("性能"));
        performance.addEntry(entries.startIntSlider(Component.literal("渲染间隔"), config.renderIntervalFrames, 1, 6)
                .setDefaultValue(2)
                .setSaveConsumer(value -> config.renderIntervalFrames = value)
                .setTooltip(Component.literal("数值越大越省性能，模型动画会略有降帧。"))
                .build());

        ConfigCategory interaction = builder.getOrCreateCategory(Component.literal("交互"));
        interaction.addEntry(entries.startBooleanToggle(Component.literal("允许拖动"), config.dragEnabled)
                .setDefaultValue(true)
                .setSaveConsumer(value -> config.dragEnabled = value)
                .build());
        interaction.addEntry(entries.startBooleanToggle(Component.literal("编辑模式"), config.editMode)
                .setDefaultValue(false)
                .setSaveConsumer(value -> config.editMode = value)
                .setTooltip(Component.literal("在背包等菜单中显示选框与缩放把手；游戏操作时自动隐藏且不接管鼠标。按 F7 也可快速切换。"))
                .build());
        interaction.addEntry(entries.startBooleanToggle(Component.literal("在菜单中显示"), config.renderInScreens)
                .setDefaultValue(true)
                .setSaveConsumer(value -> config.renderInScreens = value)
                .setTooltip(Component.literal("普通模式也会在背包、箱子、聊天、暂停菜单等所有 GUI 中持续显示。"))
                .build());
        interaction.addEntry(entries.startBooleanToggle(Component.literal("点击交互"), config.clickInteraction)
                .setDefaultValue(true)
                .setSaveConsumer(value -> config.clickInteraction = value)
                .setTooltip(Component.literal("点击模型时播放 Tap 动作。"))
                .build());
        interaction.addEntry(entries.startBooleanToggle(Component.literal("点击随机表情"), config.randomExpressionOnClick)
                .setDefaultValue(false)
                .setSaveConsumer(value -> config.randomExpressionOnClick = value)
                .build());
        interaction.addEntry(entries.startBooleanToggle(Component.literal("显示状态"), config.showStatus)
                .setDefaultValue(false)
                .setSaveConsumer(value -> config.showStatus = value)
                .build());
        return builder.build();
    }

    private static String motionModeName(Enum<?> value) {
        return switch (value.name()) {
            case "AUTO" -> "自动";
            case "SELECTED" -> "固定动作";
            case "RANDOM" -> "随机";
            case "OFF" -> "关闭";
            default -> value.name();
        };
    }

    private static String[] selectorArray(List<String> values, String selected) {
        List<String> result = new ArrayList<>(values);
        if (selected != null && !selected.isBlank() && !result.contains(selected)) {
            result.add(selected);
        }
        if (result.isEmpty()) {
            result.add("");
        }
        return result.toArray(String[]::new);
    }
}
