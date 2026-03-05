package lesson10

import de.fabmax.kool.KoolApplication   // Запускает Kool-приложение
import de.fabmax.kool.addScene          // функция - добавить сцену (UI, игровой мир и тд)

import de.fabmax.kool.math.Vec3f        // 3D - вектор (x,y,z)
import de.fabmax.kool.math.deg          // deg - превращение числа в градусы
import de.fabmax.kool.scene.*           // Сцена, камера, источники света и тд

import de.fabmax.kool.modules.ksl.KslPbrShader  // готовый PBR Shader - материал
import de.fabmax.kool.util.Color        // Цветовая палитра
import de.fabmax.kool.util.Time         // Время deltaT - сколько прошло секунд между двумя кадрами

import de.fabmax.kool.pipeline.ClearColorLoad // Режим говорящий не очищать экран от элементов (нужен для UI)

import de.fabmax.kool.modules.ui2.*     // импорт всех компонентов интерфейса, вроде text, button, Row....
import de.fabmax.kool.modules.ui2.UiModifier.*

// Flow корутины
import kotlinx.coroutines.launch                    // запускает корутину
import kotlinx.coroutines.Job                       // контроллер запущенной корутины
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive                  // проверка жива ли ещё корутина -
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

import kotlinx.serialization.Serializable           // аннотация, что можно сохранять
import kotlinx.serialization.encodeToString         // Запись в файл
import kotlinx.serialization.decodeFromString       // Чтение с файла
import kotlinx.serialization.json.Json
import lesson9.CooldownManager
import lesson9.EffectManager
import lesson9.SharedActions

import java.io.File

@Serializable
data class PlayerSave(
    val playerId: String,
    val hp: Int,
    val gold: Int,
    val poisonTicksLeft: Int,
    val attackCooldownMsLeft: Long,
    val questState: String
)

// События игровые - Flow будет рассылать их всем системам

sealed interface GameEvent{
    val playerId: String
}

data class AttackPressed(
    override val playerId: String,
    val targetId: String
): GameEvent

data class DamageDealt(
    override val playerId: String,
    val targetId: String,
    val amount: Int
): GameEvent

data class ChoiceSelected(
    override val playerId: String,
    val npcId: String,
    val choiceId: String
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val questId: String,
    val newState: String
): GameEvent

data class PoisonApplied(
    override val playerId: String,
    val ticks: Int,
    val damagePerTicks: Int,
    val intervalMs: Long
): GameEvent

data class TalkedToNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class SaveRequested(
    override val playerId: String
): GameEvent

class GameServer{
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    // Дополнительный небольшой буфер, что Emit при рассылке событий чаще проходил не упираясь в ограничение буфера

    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to PlayerSave("Oleg", 100, 0, 0, 0L, "START"),
            "Stas" to PlayerSave("Stas", 100, 0, 0, 0L, "START")
        )
    )

    val players: StateFlow<Map<String, PlayerSave>> = _players.asStateFlow()

    fun tryPublish(event: GameEvent): Boolean{
        return _events.tryEmit(event)
    }

    suspend fun publish(event: GameEvent){
        _events.emit(event)
    }

    fun updatePlayer(playerId: String, change: (PlayerSave) -> PlayerSave){
        // change - функция который берет старый PlayerSave и возвращает новый

        val oldMap = _players.value
        val oldPlayer = oldMap[playerId] ?: return

        val newPlayer = change(oldPlayer)

        val newMap = oldMap.toMutableMap()
        newMap[playerId] = newPlayer

        _players.value = newMap.toMutableMap()
    }

    fun getPlayer(playerId: String): PlayerSave{
        return _players.value[playerId] ?: PlayerSave(playerId, 100, 0, 0, 0L, "START")
    }
}

class DamageSystem(
    private val server: GameServer
){
    fun onEvent(e: GameEvent){
        if(e is DamageDealt){
            server.updatePlayer(e.playerId){ player ->
                val newHp = (player.hp - e.amount).coerceAtLeast(0)

                player.copy(hp = newHp)
            }
        }
    }
}

class CooldownSystem(
    private val server: GameServer,
    private val scope: kotlinx.coroutines.CoroutineScope
){
    private val cooldownJobs = mutableMapOf<String, Job>()

    fun startCooldown(playerId: String, totalMs: Long){
        cooldownJobs[playerId]?.cancel()

        server.updatePlayer(playerId) {player -> player.copy(attackCooldownMsLeft = totalMs)}

        val job = scope.launch {
            val step = 100L

            while(isActive && server.getPlayer(playerId).attackCooldownMsLeft > 0L){
                delay(step)

                server.updatePlayer(playerId) { player ->
                    val left = (player.attackCooldownMsLeft - step).coerceAtLeast(0L)
                    player.copy(attackCooldownMsLeft = left)
                }
            }
        }

        cooldownJobs[playerId] = job
    }

    fun canAttack(playerId: String): Boolean{
        return server.getPlayer(playerId).attackCooldownMsLeft <= 0L
    }
}

class PoisonSystem(
    private val server: GameServer,
    private val scope: kotlinx.coroutines.CoroutineScope
){
    private val poisonJob = mutableMapOf<String, Job>()

    fun onEvent(e: GameEvent, publishDamage: (DamageDealt) -> Unit){
        if (e is PoisonApplied){
            poisonJob[e.playerId]?.cancel()

            server.updatePlayer(e.playerId) {player ->
                player.copy(poisonTicksLeft = player.poisonTicksLeft + e.ticks)
            }

            val job = scope.launch {
                while (isActive && server.getPlayer(e.playerId).attackCooldownMsLeft > 0){
                    delay(e.intervalMs)

                    server.updatePlayer(e.playerId) { player ->
                        player.copy(poisonTicksLeft = (player.poisonTicksLeft - 1).coerceAtLeast(0))
                    }

                    publishDamage(DamageDealt(e.playerId, "self", e.damagePerTicks))
                }
            }
            poisonJob[e.playerId] = job
        }
    }
}

class QuestSystem(
    private val server: GameServer,
    private val scope: kotlinx.coroutines.CoroutineScope
){
    private val questId = "q_alchemist"
    private val npcId = "alchemist"
    val quests = mutableMapOf<String, Map<String, String>>()

    fun onEvent(e: GameEvent, publish: (GameEvent) -> Unit){
        val player = server.getPlayer(e.playerId)

        when(e){
            is TalkedToNpc -> {
                if (e.npcId != npcId) return

                if (player.questState == "START"){
                    server.updatePlayer(e.playerId) { it.copy(questState = "OFFERED")}
                    publish(QuestStateChanged(e.playerId, questId, "OFFERED"))
                }
            }
            is ChoiceSelected -> {
                if (e.npcId != npcId) return

                if (player.questState == "OFFERED"){
                    val newState =
                        if (e.choiceId == "help") "GOOD_END"
                        else "EVIL_END"

                    server.updatePlayer(e.playerId) {it.copy(questState = newState)}
                    publish(QuestStateChanged(e.playerId, questId, newState))
                }
            }
            else -> {}
        }
    }
}

class SaveSystem{
    private val json = Json{
        prettyPrint = true
        encodeDefaults = true
    }

    private fun file(playerId: String): File{
        val dir = File("saves")
        if(!dir.exists()) dir.mkdirs()
        return File(dir, "$playerId.json")
    }

    fun save(player: PlayerSave){
        val text = json.encodeToString(PlayerSave.serializer(), player)

        file(player.playerId).writeText(text)
    }
    suspend fun load(playerId: String): PlayerSave?{
        val file = file(playerId)
        if(!file.exists()) return null

        val text = file.readText()

        return try {
            json.decodeFromString(PlayerSave.serializer(), text)
        }catch (e: Exception){
            null
        }
    }
}

class HudState{
    val activePlayerId = mutableStateOf("Oleg")

    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)
    val poisonTicksLeft = mutableStateOf(0)
    val questState = mutableStateOf("START")
    val attackCooldownMsLeft = mutableStateOf(0L)

    val log =mutableStateOf<List<String>>(emptyList())
}

fun hudLog(hud: HudState, line: String){
    hud.log.value = (hud.log.value + line).takeLast(20)
}

object Shared{
    var server: GameServer? = null
    var saves: SaveSystem? = null
    var cooldowns: CooldownSystem? = null
    var quests: QuestSystem? = null
    var poison: PoisonSystem? = null
    var damage: DamageSystem? = null
}

fun main() = KoolApplication {
    val hud = HudState()

    addScene {
        defaultOrbitCamera()

        addColorMesh {
            generate { cube { colored() } }

            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0.7f)
                roughness(0.4f)
            }

            onUpdate {
                transform.rotate(45f.deg * Time.deltaT, Vec3f.X_AXIS)
            }
        }

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }

        val server = GameServer()

        val saves = SaveSystem()
        val damage = DamageSystem(server)
        val cooldowns = CooldownSystem(server, coroutineScope)
        val poison = PoisonSystem(server, coroutineScope)
        val quests = QuestSystem(server, coroutineScope)

        Shared.server = server
        Shared.saves = saves
        Shared.damage = damage
        Shared.cooldowns = cooldowns
        Shared.poison = poison
        Shared.quests = quests

        coroutineScope.launch {
            server.events.collect { event ->
                damage.onEvent(event)
            }
        }

        coroutineScope.launch{
            server.events.collect { event ->
                poison.onEvent(event) { dmg ->
                    if (!server.tryPublish(dmg)){
                        coroutineScope.launch { server.publish(dmg) }
                    }
                }
            }
        }

        coroutineScope.launch {
            server.events.collect { event ->
                quests.onEvent(event) { newEvent ->
                    if (!server.tryPublish(newEvent)){
                        coroutineScope.launch { server.publish(newEvent) }
                    }
                }
            }
        }

        coroutineScope.launch {
            server.events.collect { event ->
                if (event is SaveRequested) {
                    val snapShot = server.getPlayer(event.playerId)
                    saves.save(snapShot)
                }
            }
        }
    }

    addScene {
        setupUiScene(ClearColorLoad)

        val server = Shared.server

        if (server != null){
            coroutineScope.launch {
                server.events.collect { event ->
                    val line = when (event){
                        is AttackPressed -> "${event.playerId} атаковал ${event.targetId}"
                        is DamageDealt -> "${event.targetId} получил ${event.amount} урона"
                        is PoisonApplied -> "на ${event.playerId} наложен яд на ${event.ticks} тиков"
                        is TalkedToNpc -> "${event.playerId} начал разговор с ${event.npcId}"
                        is ChoiceSelected -> "${event.playerId} выбрал ${event.choiceId}"
                        is SaveRequested -> "Запрос на сохранение"
                        is QuestStateChanged -> "${event.playerId} перевел на новый этап квеста ${event.newState}"
                        else -> "Неизвестное событие"
                    }

                    hudLog(hud, line)
                }
            }
            coroutineScope.launch {
                server.players.collect { playersMap ->
                    val pid = hud.activePlayerId.value
                    val player = playersMap[pid] ?: return@collect

                    hud.hp.value = player.hp
                    hud.gold.value = player.gold
                    hud.poisonTicksLeft.value = player.poisonTicksLeft
                    hud.questState.value = player.questState
                    hud.attackCooldownMsLeft.value = player.attackCooldownMsLeft
                }
            }
        }
        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))
                .padding(12.dp)
        }
    }
}