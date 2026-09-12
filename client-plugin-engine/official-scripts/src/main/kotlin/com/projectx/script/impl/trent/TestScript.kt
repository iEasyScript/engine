import com.projectx.game.combat.AbilityDebug
import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.npcs
import com.projectx.script.api.spotAnims
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.script.event.impl.Varc
import com.projectx.script.event.impl.Varpbit
import com.projectx.script.event.impl.XPDrop
import com.projectx.util.format

@ScriptDescription(
    name = "Trent's Test Script",
    version = "1.0.0",
    author = "Trent",
    description = "Test debug script for Trent's memory reading development."
)
class TestScript : Script() {
    var startTime = 0L

    override fun onStart() {
        println("Test script start.")
        startTime = System.currentTimeMillis()
    }

    override suspend fun loop() {
        println("spotanims: ")
        spotAnims.forEach {
            println(it.timeAliveMillis)
        }
        delay(500)
    }

    val varbitWhitelist = setOf(
        50782, 50783, 50784, 50785, 50786, 50787, 50788, 50789,
        50790, 50791, 50792, 50793, 50813, 50814, 50815, 50816,
        50817, 50818, 50820, 50821, 50822, 50824, 50804, 50805,
        50806, 50807, 50808, 50809, 50810, 50811, 50812
    )

    override fun onEvent(event: Event) {
        when (event) {
            is Chat -> println("[ChatEvent]: (${event.messageType}) ${event.cleanSenderName}: ${event.message}")
            is XPDrop -> println("[XPDrop]: ${event.skill} - ${format(event.gainedXp)}")
            is Varc -> AbilityDebug.debugCooldownVarc(event)
            is Varpbit -> {
                if (varbitWhitelist.contains(event.id))
                    println("[Varbit]: ${event.id}: ${event.oldValue} -> ${event.newValue}")
            }
        }
    }
}