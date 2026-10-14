package dev.foxphys;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import java.util.Locale;
import static net.minecraft.server.command.CommandManager.*;
import static dev.foxphys.Protocol.*;

public final class Commands {
    private Commands() {}
    static final String DEPRECATED_ALIAS="FoxPhys: команда /voxphys устарела и будет удалена. Используйте /foxphys или /fox.";
    @FunctionalInterface private interface Action {String run(ServerCommandSource source);}
    private static int run(CommandContext<ServerCommandSource> ctx,Action action) {
        try {
            // The warning comes first, so it is shown even when the command itself is refused.
            if (ctx.getInput().startsWith("voxphys")) {
                ctx.getSource().sendFeedback(()->Text.literal(DEPRECATED_ALIAS),false);
            }
            String result=action.run(ctx.getSource());
            ctx.getSource().sendFeedback(()->Text.literal(result),false);return 1;
        } catch(RuntimeException | LinkageError e) {
            ctx.getSource().sendError(Text.literal("FoxPhys: "+e.getMessage()));
            FoxPhysMod.LOGGER.warn("FoxPhys command failed: {}",e.toString());return 0;
        }
    }
    private static final String[] PRESET_NAMES={"rubber","jelly","cloth","rope"};
    /** /foxphys soft ...: мягкие тела (XPBD в Rust). Без коллизий с блоками, только плоскость пола. */
    private static LiteralArgumentBuilder<ServerCommandSource> soft(LabManager manager) {
        return literal("soft")
            .then(literal("spawn").then(argument("kind",StringArgumentType.word())
                .suggests((ctx,builder)->{for(String k:PRESET_NAMES)builder.suggest(k);return builder.buildFuture();})
                .executes(ctx->run(ctx,s->{
                    String kind=StringArgumentType.getString(ctx,"kind");
                    if (!java.util.Arrays.asList(PRESET_NAMES).contains(kind)) throw new IllegalArgumentException("Неизвестный вид: "+kind+" (rubber|jelly|cloth|rope)");
                    BlockPos feet=BlockPos.ofFloored(s.getPosition());
                    // 2 blocks east of the command position, 10 cm above the floor plane at feet level.
                    var out=SoftBodies.spawn(kind,feet.getX()+2.5,feet.getY()+0.1,feet.getZ()+0.5,feet.getY());
                    return "Мягкое тело #"+out.get("id").getAsInt()+" ("+kind+", частиц "+out.get("particles").getAsInt()+"). Пол на уровне ног. Блоки мира не сталкиваются с телом.";
                }))))
            .then(literal("status").executes(ctx->run(ctx,s->SoftBodies.call("list",new com.google.gson.JsonObject()).toString()))
                .then(argument("id",IntegerArgumentType.integer(1)).executes(ctx->run(ctx,s->{
                    var o=SoftBodies.withId("status",IntegerArgumentType.getInteger(ctx,"id"));
                    return String.format(Locale.ROOT,"Тело #%d: %s, центр (%.2f, %.2f, %.2f), vmax %.2f м/с, объём %.3f..%.3f, жёсткость %.2f, демпфирование %.2f",
                        o.get("id").getAsInt(),o.get("preset").getAsString(),o.getAsJsonArray("center").get(0).getAsDouble(),o.getAsJsonArray("center").get(1).getAsDouble(),o.getAsJsonArray("center").get(2).getAsDouble(),
                        o.get("vmax").getAsDouble(),o.get("volume_ratio_min").getAsDouble(),o.get("volume_ratio_max").getAsDouble(),o.get("stiffness").getAsDouble(),o.get("damping").getAsDouble());
                }))))
            .then(literal("remove").then(argument("id",IntegerArgumentType.integer(1)).executes(ctx->run(ctx,s->{
                SoftBodies.remove(IntegerArgumentType.getInteger(ctx,"id"));
                return "Мягкое тело удалено.";
            }))))
            .then(literal("pin").then(argument("id",IntegerArgumentType.integer(1))
                .then(argument("side",StringArgumentType.word())
                    .suggests((ctx,builder)->{for(String k:new String[]{"top","bottom","start","end"})builder.suggest(k);return builder.buildFuture();})
                    .executes(ctx->run(ctx,s->{
                        com.google.gson.JsonObject a=new com.google.gson.JsonObject();
                        a.addProperty("id",IntegerArgumentType.getInteger(ctx,"id"));
                        a.addProperty("side",StringArgumentType.getString(ctx,"side"));
                        return "Закреплено частиц: "+SoftBodies.call("pin",a).get("pinned").getAsInt()+".";
                    })))))
            .then(literal("stiffness").then(argument("id",IntegerArgumentType.integer(1))
                .then(argument("value",FloatArgumentType.floatArg(0.05f,100f)).executes(ctx->run(ctx,s->{
                    com.google.gson.JsonObject a=new com.google.gson.JsonObject();
                    a.addProperty("id",IntegerArgumentType.getInteger(ctx,"id"));
                    a.addProperty("value",FloatArgumentType.getFloat(ctx,"value"));
                    return "Жёсткость тела установлена: "+SoftBodies.call("stiffness",a).get("stiffness").getAsDouble()+" (1.0 = пресет).";
                })))))
            .then(literal("damping").then(argument("id",IntegerArgumentType.integer(1))
                .then(argument("value",FloatArgumentType.floatArg(0f,50f)).executes(ctx->run(ctx,s->{
                    com.google.gson.JsonObject a=new com.google.gson.JsonObject();
                    a.addProperty("id",IntegerArgumentType.getInteger(ctx,"id"));
                    a.addProperty("value",FloatArgumentType.getFloat(ctx,"value"));
                    SoftBodies.call("damping",a);
                    return "Демпфирование установлено (1/с).";
                })))))
            .then(literal("attach").then(argument("id",IntegerArgumentType.integer(1))
                .then(argument("side",StringArgumentType.word())
                    .suggests((ctx,builder)->{for(String k:SoftAttachments.SIDES)builder.suggest(k);return builder.buildFuture();})
                    .executes(ctx->run(ctx,s->SoftAttachments.attach(manager.require(s.getWorld()),
                            IntegerArgumentType.getInteger(ctx,"id"),StringArgumentType.getString(ctx,"side"),null)))
                    .then(argument("body",LongArgumentType.longArg(1))
                        .executes(ctx->run(ctx,s->SoftAttachments.attach(manager.require(s.getWorld()),
                                IntegerArgumentType.getInteger(ctx,"id"),StringArgumentType.getString(ctx,"side"),
                                LongArgumentType.getLong(ctx,"body"))))))))
            .then(literal("detach").then(argument("id",IntegerArgumentType.integer(1)).executes(ctx->run(ctx,s->
                SoftAttachments.detach(IntegerArgumentType.getInteger(ctx,"id")) ? "Крепление снято." : "Крепления не было."))));
    }
    /** Основная команда /foxphys, короткий алиас /fox и временный алиас /voxphys (миграция с VoxPhys). */
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,LabManager manager) {
        dispatcher.register(root("foxphys",manager));
        dispatcher.register(root("fox",manager));
        dispatcher.register(root("voxphys",manager));
    }
    private static LiteralArgumentBuilder<ServerCommandSource> root(String name,LabManager manager) {
        return literal(name).requires(s->s.hasPermissionLevel(2))
            .executes(ctx->run(ctx,s->"FoxPhys alpha: spawn wood/stone/boat/wing | water still/dam | spring | wind x y z | impulse id x y z | pause true/false | status | save | restore | assemble N | disassemble ID | clear | gun give | glue give | assembler give | assembler run x y z assemble/attach/disassemble | soft spawn/status/remove/pin/stiffness/damping/attach/detach | water sph still/dam/clear"))
            .then(literal("api").executes(ctx->run(ctx,s->dev.foxphys.api.FoxPhysApi.describe())))
            .then(literal("gun").then(literal("give").executes(ctx->run(ctx,s->{
                ServerPlayerEntity player=s.getPlayer();
                if (player == null) throw new IllegalStateException("Команда только для игрока");
                player.giveItemStack(new ItemStack(FoxPhysBlocks.GUN));
                return "Гравипушка выдана. ПКМ — захват, ЛКМ при захвате — запуск.";
            }))))
            .then(literal("glue").then(literal("give").executes(ctx->run(ctx,s->{
                ServerPlayerEntity player=s.getPlayer();
                if (player == null) throw new IllegalStateException("Команда только для игрока");
                player.giveItemStack(new ItemStack(FoxPhysBlocks.GLUE,16));
                return "Мёд-клей выдан (16). ПКМ по блоку склеивает связанную группу блоков в одно тело.";
            }))))
            .then(literal("assembler")
                .then(literal("give").executes(ctx->run(ctx,s->{
                    ServerPlayerEntity player=s.getPlayer();
                    if (player == null) throw new IllegalStateException("Команда только для игрока");
                    player.giveItemStack(new ItemStack(FoxPhysBlocks.ASSEMBLER_ITEM));
                    return "Физический сборщик выдан. Ставь где нужно: физика создаётся рядом сама.";
                })))
                .then(literal("run").then(argument("pos",BlockPosArgumentType.blockPos())
                    .then(argument("mode",StringArgumentType.word())
                        .suggests((ctx,builder)->{for(Assembler.Mode m:Assembler.Mode.values())builder.suggest(m.id);return builder.buildFuture();})
                        .executes(ctx->run(ctx,s->{
                            BlockPos pos=BlockPosArgumentType.getBlockPos(ctx,"pos");
                            return Assembler.run(s.getWorld(),pos,Assembler.Mode.parse(StringArgumentType.getString(ctx,"mode")));
                        }))))))
            .then(literal("spawn").then(argument("type",StringArgumentType.word())
                .suggests((ctx,builder)->{for(String type:new String[]{"wood","stone","boat","wing"})builder.suggest(type);return builder.buildFuture();})
                .executes(ctx->run(ctx,s->{
                    Lab lab=manager.ensure(s.getWorld(),BlockPos.ofFloored(s.getPosition()));float[] p=lab.local(s.getPosition());p[1]+=4;
                    long id=lab.spawn(StringArgumentType.getString(ctx,"type"),p);
                    return "Тело #"+id+" создано (масса/силы рассчитываются в Rust).";
                }))))
            .then(literal("water")
                .then(literal("still").executes(ctx->run(ctx,s->{manager.require(s.getWorld()).water(false);return "Резервуар: спокойная вода, глубина 2 м.";})))
                .then(literal("dam").executes(ctx->run(ctx,s->{manager.require(s.getWorld()).water(true);return "Резервуар: разрыв плотины 3 м → 0.35 м; замкнутые границы.";})))
                .then(literal("sph")
                    .then(literal("still").executes(ctx->run(ctx,s->{dev.foxphys.fluid.FluidPool p=dev.foxphys.fluid.FluidWater.add(s.getWorld(),BlockPos.ofFloored(s.getPosition()).up(),false);return "SPH-вода #"+p.id()+": "+p.particles()+" частиц, спокойная.";})))
                    .then(literal("dam").executes(ctx->run(ctx,s->{dev.foxphys.fluid.FluidPool p=dev.foxphys.fluid.FluidWater.add(s.getWorld(),BlockPos.ofFloored(s.getPosition()).up(),true);return "SPH-вода #"+p.id()+": "+p.particles()+" частиц, разрыв плотины.";})))
                    .then(literal("clear").executes(ctx->run(ctx,s->{int n=dev.foxphys.fluid.FluidWater.count();dev.foxphys.fluid.FluidWater.clear();return "Удалено пулов SPH-воды: "+n+".";})))))
            .then(literal("spring").executes(ctx->run(ctx,s->{long id=manager.require(s.getWorld()).springDemo();return "Пружинный подвес тела #"+id+": k=6000 Н/м, c=1400 Н·с/м. Золотой маркер — неподвижный якорь.";})))
            .then(literal("wind").then(argument("x",FloatArgumentType.floatArg(-100,100))
                .then(argument("y",FloatArgumentType.floatArg(-100,100))
                .then(argument("z",FloatArgumentType.floatArg(-100,100)).executes(ctx->run(ctx,s->{
                    JsonObject c=op("set_wind");c.add("velocity",vector(FloatArgumentType.getFloat(ctx,"x"),FloatArgumentType.getFloat(ctx,"y"),FloatArgumentType.getFloat(ctx,"z")));
                    manager.require(s.getWorld()).commands(c);return "Скорость воздуха изменена (м/с).";
                }))))))
            .then(literal("impulse").then(argument("id",LongArgumentType.longArg(1))
                .then(argument("x",FloatArgumentType.floatArg(-2_000_000,2_000_000))
                .then(argument("y",FloatArgumentType.floatArg(-2_000_000,2_000_000))
                .then(argument("z",FloatArgumentType.floatArg(-2_000_000,2_000_000)).executes(ctx->run(ctx,s->{
                    JsonObject c=op("impulse");c.addProperty("id",LongArgumentType.getLong(ctx,"id"));
                    c.add("impulse",vector(FloatArgumentType.getFloat(ctx,"x"),FloatArgumentType.getFloat(ctx,"y"),FloatArgumentType.getFloat(ctx,"z")));
                    manager.require(s.getWorld()).commands(c);return "Импульс приложен (Н·с, НЕ м/с).";
                })))))))
            .then(literal("pause").then(argument("value",BoolArgumentType.bool()).executes(ctx->run(ctx,s->{
                JsonObject c=op("pause");c.addProperty("paused",BoolArgumentType.getBool(ctx,"value"));manager.require(s.getWorld()).commands(c);return "Пауза: "+BoolArgumentType.getBool(ctx,"value");
            }))))
            .then(literal("status").executes(ctx->run(ctx,s->{
                Lab lab=manager.require(s.getWorld());Snapshot state=lab.snapshot();
                String water=state.tank()==null?"нет":String.format(Locale.ROOT,"%.3f м³, ошибка объёма %.3g",state.tank().volume(),state.tank().relativeVolumeError());
                StringBuilder where=new StringBuilder();
                for(var b:state.bodies()){
                    if(where.length()>0)where.append("; ");
                    where.append(String.format(Locale.ROOT,"#%d (%.2f, %.2f, %.2f)",b.id(),b.position()[0]+lab.origin().getX(),b.position()[1]+lab.origin().getY(),b.position()[2]+lab.origin().getZ()));
                }
                return String.format(Locale.ROOT,"Rust ABI %d | t=%.2f с | тел %d | коллайдеров %d | расчёт последнего тика %.3f мс | вода: %s | пауза %s | ошибка %s | скан: %s%s",
                    state.abi(),state.simSeconds(),state.bodies().size(),state.staticColliders(),state.lastStepUs()/1000.0,water,state.paused(),lab.failed(),lab.scanSkipped()?"часть тел вне бюджета":"полный",where.length()==0?"":" | позиции (мир): "+where);
            })))
            .then(literal("save").executes(ctx->run(ctx,s->{
                String file=manager.save(s.getWorld(),false);return "Сохранение поставлено в очередь: "+file;
            })))
            .then(literal("restore").executes(ctx->run(ctx,s->manager.restore(s.getWorld())+". Окружение (блоки, вода) не восстанавливается: оно считывается из мира.")))
            .then(literal("assemble").then(argument("size",IntegerArgumentType.integer(1,Assembler.MAX_ASSEMBLE_SIDE)).executes(ctx->run(ctx,s->{
                Lab lab=manager.ensure(s.getWorld(),BlockPos.ofFloored(s.getPosition()));int n=IntegerArgumentType.getInteger(ctx,"size");
                // Cube of n blocks, starting 2 blocks east of the command position.
                BlockPos min=BlockPos.ofFloored(s.getPosition()).add(2,0,-n/2);
                String message = lab.assemble(min,n);
                manager.persistNow(s.getWorld());
                return message;
            }))))
            .then(literal("disassemble").then(argument("id",LongArgumentType.longArg(1)).executes(ctx->run(ctx,s->{
                String message = manager.require(s.getWorld()).disassemble(LongArgumentType.getLong(ctx,"id"));
                manager.persistNow(s.getWorld());
                return message;
            }))))
            .then(soft(manager))
            .then(literal("clear").executes(ctx->run(ctx,s->{manager.clear(s.getWorld());return "Физический мир и его визуальные объекты удалены. Блоки сборок возвращены на исходные места; остальные блоки мира не изменялись.";})))
        ;
    }
}
