package com.projectx.javaapi;

import com.projectx.game.chat.MessageType;
import com.projectx.game.input.Key;
import com.projectx.game.interfaces.Bank;
import com.projectx.game.nxt.entity.location.SceneObject;
import com.projectx.script.BooleanConfigItem;
import com.projectx.script.ConfigurableScript;
import com.projectx.script.IntConfigItem;
import com.projectx.script.JavaScript;
import com.projectx.script.JavaState;
import com.projectx.script.JavaStateMachineScript;
import com.projectx.script.ScriptDescription;
import com.projectx.script.Wait;
import com.projectx.script.api.Area;
import com.projectx.script.api.DangerZone;
import com.projectx.script.api.Equipment;
import com.projectx.script.api.Familiar;
import com.projectx.script.api.GroupTeleports;
import com.projectx.script.api.Lodestone;
import com.projectx.script.api.MakeX;
import com.projectx.script.api.Prayer;
import com.projectx.script.api.TileArea;
import com.projectx.script.event.impl.XPDrop;
import com.projectx.ui.backend.dsl.ImGuiState;
import com.projectx.ui.backend.dsl.utils.ImGuiColors;
import com.projectx.webwalker.WebWalker;
import org.projectx.core.game.combat.Ability;
import org.projectx.core.game.combat.Effect;
import org.projectx.core.game.skill.Skill;
import world.gregs.voidps.type.Tile;

import java.util.List;
import java.util.Map;

import static com.projectx.script.api.APIKt.*;
import static com.projectx.ui.backend.dsl.Overlay.*;

/**
 * Compiled with the engine's tests and never run against the game: if any of this stops compiling, part of the API
 * has stopped being reachable from Java. Written the way a script developer would write it.
 */
@ScriptDescription(name = "Java API parity", version = "1.0.0", author = "Project X", description = "Compile check", visible = false)
public class JavaApiParityScript extends JavaScript implements ConfigurableScript {

    private final BooleanConfigItem bank = new BooleanConfigItem("Bank", "Bank instead of dropping");
    private final IntConfigItem range = new IntConfigItem("Range", "Search range", 20);
    private boolean castWorked;

    @Override
    public Wait onLoop() {
        // Tile-taking helpers, in coordinate form.
        SceneObject rock = findClosestObjectToTile(3200, 3200, 0, "Rocks");
        SceneObject tree = findClosestObjectToTile(3200, 3200, 0, range.getValue(), false, o -> o.hasOption("Chop down"));
        List<SceneObject> nearby = getAllObjectsWithinRange(3200, 3200, 0, 15);
        boolean clicked = interactClosestReachableObjectToTile(3200, 3200, 0, "Mine");
        walkToTile(3200, 3200, 0);
        diveToTile(3200, 3200, 0);
        if (rock != null && tree != null) System.out.println(rock.getPlane() + nearby.size() + (clicked ? 1 : 0));

        Tile safe = calculateClosestSafeTile(3200, 3200, 0, List.of(new DangerZone(3201, 3201, 0, 1)));
        if (safe != null) System.out.println(safe.getX() + "," + safe.getY());
        boolean clear = bresenhamLos(3200, 3200, 3205, 3205, List.of(new TileArea(3202, 3202, 0, 1, 1)));
        boolean inside = new Area.Rectangular(3190, 3190, 3210, 3210, 0).contains(3200, 3200, 0)
            && new Area.Circular(3200, 3200, 0, 5.0).contains(3201, 3201, 0)
            && new Area.Polygonal(new int[]{3190, 3210, 3200}, new int[]{3190, 3190, 3210}, 0).contains(3200, 3195, 0);
        Tile somewhere = new Area.Rectangular(3190, 3190, 3210, 3210, 0).randomWalkableTile();
        if (somewhere != null) walkToTile(somewhere.getX(), somewhere.getY(), somewhere.getLevel());
        int lodestoneX = Lodestone.VARROCK.getTileX() + Lodestone.VARROCK.getPlane();
        if (clear && inside && lodestoneX > 0 && bank.getValue()) return Wait.ms(100);

        // Objects that used to need INSTANCE.
        boolean makeXOpen = MakeX.isOpen();
        Bank.doBankAction(Bank.getCLOSE_COMPONENT_ID());
        Equipment.Slot.getItem(Equipment.Slot.HEAD);
        int white = ImGuiColors.getWHITE();
        WebWalker.findPathAsync(3200, 3200, 3210, 3210, 0);
        if (makeXOpen && white != 0) return Wait.abort();

        // Every Kotlin helper that waits, as a Wait.
        return Wait.sequence(
            () -> Wait.castAndWaitForCd(Ability.SURGE, ok -> castWorked = ok),
            () -> Wait.castWithAdren(Ability.SURGE, 50),
            () -> Wait.castIf(castWorked, Ability.SURGE, () -> !castWorked),
            () -> Wait.smartCast(Ability.SURGE, ok -> {}, 0, () -> true, null, 6200, true),
            () -> Wait.castWithEffectStacks(Ability.SURGE, Effect.OVERLOADED, 2),
            () -> Wait.togglePrayer(Prayer.SORROW, true),
            () -> Wait.toggleQuickPrayers(false),
            () -> Wait.clickKey(Key.RETURN),
            () -> Wait.clickKey('1'),
            () -> Wait.findAndPickupItems("Bones", "Coins"),
            () -> Wait.checkPorter(),
            () -> Wait.useLodestone(Lodestone.VARROCK),
            () -> Wait.teleportWithGroupSystem(GroupTeleports.CROESUS),
            () -> Wait.randomizedWorldHop(),
            () -> Wait.randomizedWorldHopQuick(1),
            () -> Wait.checkWorldPop(hopped -> {}),
            () -> Wait.makeX(name -> name.contains("bar")),
            () -> Wait.makeXSelect(name -> name.contains("bar"), category -> true, ok -> {}),
            () -> Wait.makeXConfirm(),
            () -> Wait.selectMakeCategory(name -> true),
            () -> Wait.smithSetQuantity(10, quantity -> {}),
            () -> Wait.smithSelectTier(2),
            () -> Wait.smithSelectItem(name -> true),
            () -> Wait.smithMake(),
            () -> Wait.familiarSummonFamiliar(Familiar.SPIRIT_WOLF),
            () -> Wait.familiarRenewFromBank(Familiar.SPIRIT_WOLF, ok -> {}),
            () -> Wait.familiarCastSpecial("Rock"),
            () -> Wait.bobStoreByName(Map.of("Iron ore", 28)),
            () -> Wait.bobWithdrawById(Map.of(440, 28), ok -> {}),
            () -> Wait.bobGiveAllItems(),
            () -> Wait.startOrRejoinInstance(ok -> {}),
            () -> Wait.joinInstance(),
            () -> Wait.event(event -> event instanceof XPDrop, 5000),
            () -> Wait.chatContaining(MessageType.PUBLIC_CHAT, "hello"),
            () -> Wait.untilNotMoving(),
            () -> Wait.pauseOthersFor(1000),
            () -> Wait.webWalk(3210, 3210, 0, 2, result -> System.out.println(result.isSuccess())),
            () -> Wait.xpDrop(Skill.MINING)
        );
    }

    @Override
    protected boolean shouldInterrupt() {
        return false;
    }

    @Override
    public void render() {
        ImGuiState<Boolean> showMore = persistentState("parity.more", () -> boolState(false));
        setNextWindowSize(300f, 200f, COND_FIRST_USE_EVER);
        window("Java API parity", WINDOW_NO_COLLAPSE | WINDOW_ALWAYS_AUTO_RESIZE, w -> {
            section(w, "Status");
            text(w, "Running");
            xpProgressBar(w, Skill.MINING);
            button(w, "Stop", this::stop);
            checkbox(w, "Show more", showMore);
            combo(w, "Mode", 0, List.of("A", "B"), index -> {});
            properties(w, "stats", table -> {
                valueRow(table, "Ores", "12");
                row(table, "Range", cell -> inputInt(cell, "##range", range.getValue(), range::setValue));
            });
            tabBar(w, "tabs", tabs -> tabItem(tabs, "Main", tab -> text(tab, "Main tab")));
            collapsingHeader(w, "Details", details -> text(details, "More"));
        });
        backgroundDrawList(draw -> {
            draw.tile(3200, 3200, 0, ImGuiColors.getGREEN());
            draw.textOnTile(3200, 3200, 0, ImGuiColors.getWHITE(), "Here");
        });
    }

    /** A state machine in Java, phases as an enum. */
    public static final class Miner extends JavaStateMachineScript<Miner> {
        @Override
        public JavaState<Miner> getStartState() {
            return Phase.MINING;
        }
    }

    enum Phase implements JavaState<Miner> {
        MINING {
            @Override
            public JavaState<Miner> checkNext(Miner script) {
                return getInventory().isFull() ? BANKING : null;
            }

            @Override
            public Wait onLoop(Miner script) {
                return interactClosestObject("Rocks", "Mine") ? Wait.xpDrop() : Wait.ms(300, 100);
            }
        },
        BANKING {
            @Override
            public JavaState<Miner> checkNext(Miner script) {
                return getInventory().isEmpty() ? MINING : null;
            }

            @Override
            public Wait onLoop(Miner script) {
                return Wait.webWalk(3185, 3436, 0);
            }
        }
    }
}
