package mod.azure.ovomorphosis.config;

import mod.azure.azurelib.common.config.Config;
import mod.azure.azurelib.common.config.Configurable;

import mod.azure.ovomorphosis.CommonMod;

@Config(id = CommonMod.MOD_ID)
public class OvomorphosisConfig {

    @Configurable
    @Configurable.Synchronized
    public boolean enableIncrementalPathfinding = true;

    @Configurable
    @Configurable.Synchronized
    @Configurable.Range(min = 25)
    public int incrementalPathfindingNodeBudget = 300;

    @Configurable
    @Configurable.Synchronized
    @Configurable.DecimalRange(min = 1200)
    public float eggmorphTotalTicks = 1200;

    @Configurable
    @Configurable.Synchronized
    @Configurable.Range(min = 2400)
    public int infectionMinTicks = 2400;

    @Configurable
    @Configurable.Synchronized
    @Configurable.Range(min = 6000)
    public int infectionMaxTicks = 6000;

    @Configurable
    @Configurable.Synchronized
    public boolean enableAcidBlockBreaking = true;

    @Configurable
    @Configurable.Synchronized
    public boolean enableAcidItemBreaking = true;

    @Configurable
    @Configurable.Synchronized
    @Configurable.Range(min = 1)
    public int acidDestroySpeedMultiplier = 3;

    @Configurable
    @Configurable.Synchronized
    public ItemConfigs itemConfigs = new ItemConfigs();

    public static class ItemConfigs {

        @Configurable
        @Configurable.Synchronized
        public boolean disableInfectionScannerTimeOutput = true;

        @Configurable
        @Configurable.Synchronized
        @Configurable.DecimalRange(min = 0.0D)
        public float infectionScannerSoundVolume = 0.4F;
    }

    @Configurable
    @Configurable.Synchronized
    public BlockConfigs blockConfigs = new BlockConfigs();

    public static class BlockConfigs {

        @Configurable
        @Configurable.Synchronized
        public boolean enableResinBlockTicking = true;
    }

    @Configurable
    @Configurable.Synchronized
    public EntityConfigs entityConfigs = new EntityConfigs();

    public static class EntityConfigs {

        @Configurable
        @Configurable.Synchronized
        public OvomorphConfigs ovomorphConfigs = new OvomorphConfigs();

        public static class OvomorphConfigs {

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double ovomorphHealth = 10.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double ovomorphArmor = 1.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double ovomorphArmorToughness = 0.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double ovomorphKnockbackRes = 0.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double ovomorphSearchRange = 6D;
        }

        @Configurable
        @Configurable.Synchronized
        public FacehuggerConfigs facehuggerConfigs = new FacehuggerConfigs();

        public static class FacehuggerConfigs {

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double facehuggerHealth = 20.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double facehuggerArmor = 1.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double facehuggerArmorToughness = 0.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double facehuggerKnockbackRes = 0.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 1200)
            public float facehuggerAttachMaxTicks = 1200;
        }

        @Configurable
        @Configurable.Synchronized
        public ChestbursterConfigs chestbursterConfigs = new ChestbursterConfigs();

        public static class ChestbursterConfigs {

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double chestbursterHealth = 30.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double chestbursterArmor = 1.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double chestbursterArmorToughness = 0.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double chestbursterKnockbackRes = 0.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 1)
            public float chestbursterFoodGrowthValue = 10;
        }

        @Configurable
        @Configurable.Synchronized
        public XenomorphConfigs xenomorphConfigs = new XenomorphConfigs();

        public static class XenomorphConfigs {

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double xenoHealth = 100.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double xenoArmor = 10.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double xenoArmorToughness = 3.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double xenoKnockbackRes = 1.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double xenoAttackDamage = 6.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double xenoHostileRange = 32.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0F)
            public float xenoExecuteChance = 0.25F;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0F)
            public float xenoCarryToResinChance = 0.08F;

            @Configurable
            @Configurable.Synchronized
            public boolean enableXenomorphItemSlap = true;

        }

        @Configurable
        @Configurable.Synchronized
        public RunnerConfigs runnerConfigs = new RunnerConfigs();

        public static class RunnerConfigs {

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double runnerHealth = 100.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double runnerArmor = 10.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double runnerArmorToughness = 3.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double runnerKnockbackRes = 1.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double runnerAttackDamage = 6.0D;

            @Configurable
            @Configurable.Synchronized
            @Configurable.DecimalRange(min = 0.0D)
            public double runnerHostileRange = 32.0D;
        }
    }
}
