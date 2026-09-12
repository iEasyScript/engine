package com.projectx.script.impl.trent.dungeoneering.auto

import com.projectx.script.impl.trent.dungeoneering.boss.DivineSkinweaver
import com.projectx.script.impl.trent.dungeoneering.boss.NightGazerBoss
import com.projectx.script.impl.trent.dungeoneering.boss.StompBoss
import com.projectx.script.impl.trent.dungeoneering.puzzle.BaitThePlatePuzzle
import com.projectx.script.impl.trent.dungeoneering.puzzle.ConstructPuzzle
import com.projectx.script.impl.trent.dungeoneering.puzzle.ConvergencePuzzle
import com.projectx.script.impl.trent.dungeoneering.puzzle.FerretPuzzle
import com.projectx.script.impl.trent.dungeoneering.puzzle.FollowLeaderPuzzle
import com.projectx.script.impl.trent.dungeoneering.puzzle.GuardRoomPuzzle
import com.projectx.script.impl.trent.dungeoneering.puzzle.JammedBridgePuzzle
import com.projectx.script.impl.trent.dungeoneering.puzzle.LightsOutPuzzle
import com.projectx.script.impl.trent.dungeoneering.puzzle.MazePuzzle
import com.projectx.script.impl.trent.dungeoneering.puzzle.MonolithPuzzle
import com.projectx.script.impl.trent.dungeoneering.puzzle.OvergrownGardenPuzzle
import com.projectx.script.impl.trent.dungeoneering.puzzle.PoltergeistPuzzle
import com.projectx.script.impl.trent.dungeoneering.puzzle.PondskaterPuzzle
import com.projectx.script.impl.trent.dungeoneering.puzzle.PressurePenPuzzle
import com.projectx.script.impl.trent.dungeoneering.puzzle.RockPaperScissorsPuzzle
import com.projectx.script.impl.trent.dungeoneering.puzzle.RogueRunPuzzle
import com.projectx.script.impl.trent.dungeoneering.puzzle.SlidingStatuesPuzzle
import com.projectx.script.impl.trent.dungeoneering.puzzle.SomethingsMissingPuzzle
import com.projectx.script.impl.trent.dungeoneering.puzzle.SynchSwitchPuzzle
import com.projectx.script.impl.trent.dungeoneering.puzzle.UnmappedPuzzleRoom

/**
 * Every dungeon room handler in priority order. The running bot walks this list each tick and hands the room to
 * the first handler whose `present()` matches — bosses first (they own combat + positioning, gated on
 * `inBossRoom` so they only engage once the navigator has crossed in), then the puzzle rooms whose doors would
 * otherwise be force-poked forever. Adding a puzzle or boss is a single entry here; the bot loop never changes.
 */
object DungeonRooms {
    val registered: List<DungeonRoom> = listOf(
        DungeonRoom("Divine Skinweaver", postDelayMs = 400,
            present = { it.inBossRoom && !it.roomGivenUp && DivineSkinweaver.present() },
            solve = { DivineSkinweaver.fight(it.script) }),
        DungeonRoom("Stomp", postDelayMs = 300,
            present = { it.inBossRoom && !it.roomGivenUp && StompBoss.present() },
            solve = { StompBoss.fight(it.script) }),
        DungeonRoom("Night-gazer Khighorahk", postDelayMs = 300,
            present = { it.inBossRoom && !it.roomGivenUp && NightGazerBoss.present() },
            solve = { NightGazerBoss.fight(it.session, it.script) }),
        DungeonRoom("Convergence",
            present = { ConvergencePuzzle.present() },
            solve = { ConvergencePuzzle.solve(it.script) }),
        DungeonRoom("Pondskater",
            present = { PondskaterPuzzle.present(it.session) },
            solve = { PondskaterPuzzle.solve(it.session, it.script) }),
        DungeonRoom("Ferret",
            present = { FerretPuzzle.present(it.session) },
            solve = { FerretPuzzle.solve(it.session, it.script) }),
        DungeonRoom("Construct",
            present = { ConstructPuzzle.present() },
            solve = { ConstructPuzzle.solve(it.script) }),
        DungeonRoom("Rogue Run",
            present = { RogueRunPuzzle.present() },
            solve = { RogueRunPuzzle.solve(it.script) }),
        DungeonRoom("Guard Room",
            present = { GuardRoomPuzzle.present() },
            solve = { GuardRoomPuzzle.solve(it.script) }),
        DungeonRoom("Pressure Pen",
            present = { PressurePenPuzzle.present() },
            solve = { PressurePenPuzzle.solve(it.script) }),
        DungeonRoom("Poltergeist",
            present = { PoltergeistPuzzle.present(it.session) },
            solve = { PoltergeistPuzzle.solve(it.session, it.script) }),
        DungeonRoom("Monolith",
            present = { MonolithPuzzle.present() },
            solve = { MonolithPuzzle.solve(it.script) }),
        DungeonRoom("Follow the Leader",
            present = { FollowLeaderPuzzle.present(it.session) },
            solve = { FollowLeaderPuzzle.solve(it.session, it.script) }),
        DungeonRoom("Something's Missing",
            present = { SomethingsMissingPuzzle.present() },
            solve = { SomethingsMissingPuzzle.solve(it.script) }),
        DungeonRoom("Jammed Bridge",
            present = { JammedBridgePuzzle.present() },
            solve = { JammedBridgePuzzle.solve(it.script) }),
        DungeonRoom("Lights Out",
            present = { LightsOutPuzzle.present(it.session) },
            solve = { LightsOutPuzzle.solve(it.session, it.script) }),
        DungeonRoom("Rock Paper Scissors",
            present = { RockPaperScissorsPuzzle.present(it.session) },
            solve = { RockPaperScissorsPuzzle.solve(it.session, it.script) }),
        DungeonRoom("Bait the Plate",
            present = { BaitThePlatePuzzle.present(it.session) },
            solve = { BaitThePlatePuzzle.solve(it.session, it.script) }),
        DungeonRoom("Sliding Statues",
            present = { SlidingStatuesPuzzle.present() },
            solve = { SlidingStatuesPuzzle.solve(it.script) }),
        DungeonRoom("Synchronised Switches",
            present = { SynchSwitchPuzzle.present(it.session) },
            solve = { SynchSwitchPuzzle.solve(it.session, it.script) }),
        DungeonRoom("Overgrown Garden",
            present = { OvergrownGardenPuzzle.present() },
            solve = { OvergrownGardenPuzzle.solve(it.script) }),
        DungeonRoom("Maze",
            present = { MazePuzzle.present(it.session) },
            solve = { MazePuzzle.solve(it.session, it.script) }),
        // Last on purpose: only rooms every real handler declined reach this, where it reports the mechanic
        // instead of leaving the navigator to poke a frozen door forever.
        DungeonRoom("Unmapped puzzle",
            present = { UnmappedPuzzleRoom.present(it.session) },
            solve = { UnmappedPuzzleRoom.solve(it.script) }),
    )
}
