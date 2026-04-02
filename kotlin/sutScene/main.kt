package cutScenes

import de.fabmax.kool.KoolApplication           // KoolApplication - запускает Kool-приложение (окно + цикл рендера)
import de.fabmax.kool.addScene                  // addScene - функция "добавь сцену" в приложение (у тебя она просила отдельный импорт)
import de.fabmax.kool.math.Vec3f                // Vec3f - 3D-вектор (x, y, z), как координаты / направление
import de.fabmax.kool.math.deg                  // deg - превращает число в "градусы" (угол)
import de.fabmax.kool.modules.audio.synth.SampleNode
import de.fabmax.kool.scene.*                   // scene.* - Scene, defaultOrbitCamera, addColorMesh, lighting и т.д.
import de.fabmax.kool.modules.ksl.KslPbrShader  // KslPbrShader - готовый PBR-шейдер (материал)
import de.fabmax.kool.util.Color                // Color - цвет (RGBA)
import de.fabmax.kool.util.Time                 // Time.deltaT - сколько секунд прошло между кадрами
import de.fabmax.kool.pipeline.ClearColorLoad   // ClearColorLoad - режим: "не очищай экран, оставь то что уже нарисовано"
import de.fabmax.kool.modules.ui2.*             // UI2: addPanelSurface, Column, Row, Button, Text, dp, remember, mutableStateOf
import de.fabmax.kool.physics.joints.DistanceJoint
import jdk.jfr.DataAmount
import jdk.jfr.StackTrace

import kotlinx.coroutines.launch                    // запуск корутин
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay

// Flow корутины
import kotlinx.coroutines.flow.MutableSharedFlow    // радиостанция событий
import kotlinx.coroutines.flow.SharedFlow           // чтение для подписчиков
import kotlinx.coroutines.flow.MutableStateFlow     // табло состояний
import kotlinx.coroutines.flow.StateFlow            // только для чтения
import kotlinx.coroutines.flow.asSharedFlow         // отдать наружу только SharedFlow
import kotlinx.coroutines.flow.asStateFlow          // отдать только StateFlow

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.processNextEventInCurrentThread
import kotlinx.serialization.modules.SerializersModule
import realGameScene.GameServer
import realGameScene.HudState
import javax.accessibility.AccessibleValue
import javax.management.ValueExp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class QuestState{
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END
}

// типы объектов
enum class WorldObjectType{
    ALCHEMIST,
    HERB_SOURCE,
    CHEST
}

// описание объектов в игровом мире
data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val x: Float,
    val z: Float,
    val interactRadius: Float
)

data class NpcMemory(
    val hasMet: Boolean,
    val timesTalked: Int,
    val receivedHerb: Boolean,
    val sawPlayerNearSource: Boolean = false
)

data class PlayerState(
    val playerId: String,
    val posX: Float,
    val posZ: Float,
    val questState: QuestState,
    val inventory: Map<String, Int>,
    val alchemistMemory: NpcMemory,
    val currentAreaId: String?,
    val hintText: String,
    val gold: Int,
    val nearHerbSource: Boolean,
    val nearAlchemist: Boolean,

    val inputLocked: Boolean,
    val cutsceneActive: Boolean,
    val cutsceneText: String
)


fun herbCount(player: PlayerState): Int{
    return player.inventory["herb"] ?: 0
}


//d = √((x₂ - x₁)² + (y₂ - y₁)²)
fun distance2D(ax: Float, az: Float, bx: Float, bz: Float): Float{
    val dx = ax - bx
    val dz = az - bz
    return sqrt(dx*dx + dz*dz)
}

fun initialPlayerState(playerId: String): PlayerState {
    return if(playerId == "Stas"){
        PlayerState(
            "Stas",
            0f,
            0f,
            QuestState.START,
            emptyMap(),
            NpcMemory(
                true,
                2,
                false
            ),
            null,
            "Подойди к одной из локаций",
            3,
            false,
            false,
            false,
            false,
            ""
        )
    }else{
        PlayerState(
            "Oleg",
            0f,
            0f,
            QuestState.START,
            emptyMap(),
            NpcMemory(
                false,
                0,
                false
            ),
            null,
            "Подойди к одной из локаций",
            3,
            false,
            false,
            false,
            false,
            ""
        )
    }
}

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val npcId: String,
    val text: String,
    val option: List<DialogueOption>
)

fun buildAlchemistDialogue(player: PlayerState): DialogueView{
    // если идет катсцена - обычный диалог - отключаем
    if (player.cutsceneActive){
        return DialogueView(
            "Алхимик",
            "Сейчас идет катсцена",
            emptyList()
        )
    }

    if (!player.nearAlchemist){
        return DialogueView(
            "Алхимик",
            "Подойди ближе к Алхимику",
            emptyList()
        )
    }

    val herbs = herbCount(player)
    val memory = player.alchemistMemory

    return when(player.questState){
        QuestState.START -> {
            val greeting =
                if (!memory.hasMet){
                    "О привет"
                }else{
                    "снова ьы... я тебя знаю, ты ${player.playerId}"
                }
            DialogueView(
                "Алхимик",
                "$greeting \n Хочешь помочь - принеси травку",
                listOf(
                    DialogueOption("accept_help", "Я принесу траву"),
                    DialogueOption("threat", "травы не будет, гони товар")
                )
            )
        }

        QuestState.WAIT_HERB ->{
            if (herbs < 3){
                DialogueView(
                    "Алхимик",
                    "Недостаточно, надо $herbs/4 травы",
                    emptyList()
                )
            }else{
                DialogueView(
                    "Алхимик",
                    "найс, прет как белый, давай сюда",
                    listOf(
                        DialogueOption("give_herb", "Отдать 4 травы")
                    )
                )
            }
        }

        QuestState.GOOD_END -> {
            val text =
                if (memory.receivedHerb){
                    "Спасибо спасибо"
                }else{
                    "Ты завершил квест, но нпс все забыл..."
                }
            DialogueView(
                "Алхимик",
                text,
                emptyList()
            )
        }

        QuestState.EVIL_END -> {

            DialogueView(
                "Алхимик",
                "ты проиграл бетмен",
                emptyList()
            )
        }
    }
}

sealed interface GameCommand{
    val playerId: String
}

data class CmdMovePlayer(
    override val playerId: String,
    val dx: Float,
    val dz: Float
): GameCommand

data class CmdMoveNpc(
    val dx: Float,
    val dz: Float
)

data class CmdInteract(
    override val playerId: String
): GameCommand

data class CmdChooseDialogueOption(
    override val playerId: String,
    val optionId: String
): GameCommand

data class CmdResetPlayer(
    override val playerId: String
): GameCommand

sealed interface GameEvent{
    val playerId: String
}

data class EnteredArea(
    override val playerId: String,
    val areaId: String
): GameEvent

data class LeftArea(
    override val playerId: String,
    val areaId: String
): GameEvent

data class InteractedWithNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class InteractedWithHerbSource(
    override val playerId: String,
    val sourceId: String
): GameEvent

data class InteractedWithChest(
    override val playerId: String,
    val sourceId: String
): GameEvent

data class GoldCountChanged(
    override val playerId: String,
    val countGold: Int
): GameEvent

data class InventoryChanged(
    override val playerId: String,
    val itemId: String,
    val newCount: Int
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val newState:
    QuestState
): GameEvent

data class NpcMemoryChanged(
    override val playerId: String,
    val memory:
    NpcMemory
): GameEvent

data class ServerMessage(
    override val playerId: String,
    val text: String
): GameEvent

data class CutSceneStarted(
    override val playerId: String,
    val cutsceneId: String
): GameEvent

data class CutSceneStep(
    override val playerId: String,
    val text: String
): GameEvent

data class CutSceneFinished(
    override val playerId: String,
    val cutsceneId: String
): GameEvent


class GameServer {
    val worldObjects = listOf(
        WorldObjectDef(
            "alchemist",

            WorldObjectType.ALCHEMIST,
            -3f,
            0f,
            1.7f
        ),
        WorldObjectDef(
            "herb_source",

            WorldObjectType.HERB_SOURCE,
            3f,
            0f,
            1.7f
        ),
        WorldObjectDef(
            "treasure_box",

            WorldObjectType.CHEST,
            7f,
            0f,
            1.7f
        )
    )

    private val _events = MutableSharedFlow<
            GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<
            GameEvent> = _events.asSharedFlow()

    private val _commands = MutableSharedFlow<
            GameCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<
            GameCommand> = _commands.asSharedFlow()

    fun trySend(cmd:
                GameCommand): Boolean = _commands.tryEmit(cmd)

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to initialPlayerState("Oleg"),
            "Stas" to initialPlayerState("Stas")
        )
    )

    val players: StateFlow<Map<String,
            PlayerState>> = _players.asStateFlow()

    private val _treasureChestVisible = MutableStateFlow(false)
    val treasureChestVisible: StateFlow<Boolean> = _treasureChestVisible.asStateFlow()

    fun isTreasureChestVisible(): Boolean = _treasureChestVisible.value

    fun start(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            commands.collect { cmd ->
                processCommand(cmd)
            }
        }
    }

    fun setPlayerData(playerId: String, data:
    PlayerState) {
        val map = _players.value.toMutableMap()
        map[playerId] = data
        _players.value = map.toMap()
    }

    fun getPlayerData(playerId: String):
            PlayerState {
        return _players.value[playerId] ?: initialPlayerState(playerId)
    }

    private fun updatePlayer(playerId: String, change: (
        PlayerState) ->
    PlayerState) {
        val oldMap = _players.value
        val oldPlayer = oldMap[playerId] ?: return

        val newPlayer = change(oldPlayer)

        val newMap = oldMap.toMutableMap()
        newMap[playerId] = newPlayer
        _players.value = newMap.toMap()
    }

    // cutsceneJobs[playerId] = текущая катсцена этого игрока
    private val cutsceneJobs = mutableMapOf<String, Job>()

    private var serverScope: kotlinx.coroutines.CoroutineScope? = null



    private fun nearestObject(player: PlayerState): WorldObjectDef? {
        val candidates = worldObjects.filter { obj ->
            distance2D(player.posX, player.posZ, obj.x, obj.z) <= obj.interactRadius
        }

        return candidates.minByOrNull { obj ->
            distance2D(player.posX, player.posZ, obj.x, obj.z)
        }

        // minBy - берет ближайший объект до игрока
        // OrNull - если таковых нет -> null
    }

    private suspend fun refreshPlayerArea(playerId: String){
        val player = getPlayerData(playerId)
        val nearest = nearestObject(player)

        val oldAreaId = player.currentAreaId
        val newAreaId = nearest?.id

        if (oldAreaId == newAreaId){
            val newHint =
                when (newAreaId){
                    "alchemist" -> "Подойди и нажми на алхимика"
                    "herb_source" -> "собери траву"
                    else -> "Подойди к одной из локаций"
                }
        }

        if (oldAreaId != null){
            _events.emit(LeftArea(playerId, oldAreaId))
        }

        if (newAreaId != null){
            _events.emit(EnteredArea(playerId, newAreaId))
        }

        val newHint =
            when (newAreaId){
                "alchemist" -> "Подойди и нажми на алхимика"
                "herb_source" -> "собери траву"
                else -> "Подойди к одной из локаций"
            }
        updatePlayer(playerId) { p ->
            p.copy(
                hintText = newHint,
                currentAreaId = newAreaId
            )
        }
    }

    private suspend fun processCommand(cmd: GameCommand){
        when(cmd){
            is CmdMovePlayer -> {
                val player = getPlayerData(cmd.playerId)

                // пока идет катсцена - движение запрещаем
                if (player.cutsceneActive){
                    _events.emit(ServerMessage(cmd.playerId, "Управление заблокированно, идет катсцена"))
                    return
                }

                updatePlayer(cmd.playerId) { p ->
                    p.copy(
                        posX = p.posX + cmd.dx,
                        posZ = p.posZ + cmd.dz
                    )
                }
                refreshPlayerArea(cmd.playerId)
            }

            is CmdInteract -> {
                val player = getPlayerData(cmd.playerId)
                val obj = nearestObject(player)

                if (obj == null){
                    _events.emit(ServerMessage(cmd.playerId, "Рядом нет объектов для взаимодействия"))
                    return
                }

                when (obj.type){
                    WorldObjectType.ALCHEMIST -> {
                        if (player.alchemistMemory.sawPlayerNearSource){
                            _events.emit(ServerMessage(cmd.playerId, "Так... ты тут был... ааа трава-то, где?"))
                            return
                        }else{
                            val oldMemory = player.alchemistMemory
                            val newMemory = oldMemory.copy(
                                hasMet = true,
                                timesTalked = oldMemory.timesTalked + 1
                            )



                            updatePlayer(cmd.playerId){ p ->
                                p.copy(alchemistMemory = newMemory)
                            }

                            _events.emit(InteractedWithNpc(cmd.playerId, obj.id))
                            _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                        }
                    }

                    WorldObjectType.HERB_SOURCE -> {
                        val oldAlchemistMemory = player.alchemistMemory
                        val newAlchemistMemory = oldAlchemistMemory.copy(
                            sawPlayerNearSource = true
                        )
                        updatePlayer(cmd.playerId) { p ->
                            p.copy(alchemistMemory = newAlchemistMemory)
                        }
                        if (player.questState != QuestState.WAIT_HERB){
                            _events.emit(ServerMessage(cmd.playerId, "Трава сейчас не нужна, сначала возьми квест"))
                            return
                        }

                        val oldCount = herbCount(player)
                        val newCount = oldCount + 1
                        val newInventory = player.inventory + ("herb" to newCount)

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(inventory = newInventory)
                        }

                        _events.emit(InteractedWithHerbSource(cmd.playerId, obj.id))
                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                    }

                    WorldObjectType.CHEST -> {
                        if (!_treasureChestVisible.value) {
                            _events.emit(ServerMessage(cmd.playerId, "Сундук пуст или его здесь нет..."))
                            return
                        }

                        if (player.questState != QuestState.GOOD_END) {
                            _events.emit(ServerMessage(cmd.playerId, "Сундук заперт. Нужно сначала помочь алхимику"))
                            return
                        }

                        val oldCountGold = player.gold
                        val newCountGold = oldCountGold + 1

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(gold = newCountGold)
                        }

                        _treasureChestVisible.value = false

                        _events.emit(InteractedWithChest(cmd.playerId, obj.id))
                        _events.emit(GoldCountChanged(cmd.playerId, newCountGold))
                        _events.emit(ServerMessage(cmd.playerId, "Ты открыл сундук и нашел 10 золотых монет! Сундук исчез."))
                    }
                }
            }

            is CmdChooseDialogueOption -> {
                val player = getPlayerData(cmd.playerId)

                if (player.currentAreaId != "alchemist"){
                    _events.emit(ServerMessage(cmd.playerId, "Сначала подойди к алхимику"))
                    return
                }

                when(cmd.optionId){
                    "accepted_help" -> {
                        val radiusHerb = distance2D(player.posX, player.posZ, 3f, 0f)
                        if (radiusHerb <= 1.7f){
                            if (player.questState != QuestState.START){
                                _events.emit(ServerMessage(cmd.playerId, "Путь помощи можно выбрать только в начале квеста"))
                                return
                            }

                            updatePlayer(cmd.playerId){ p ->
                                p.copy(questState = QuestState.WAIT_HERB)
                            }

                            _events.emit(QuestStateChanged(cmd.playerId, QuestState.WAIT_HERB))
                            _events.emit(ServerMessage(cmd.playerId, "Алхимик просит собрать х3 травы"))
                        }
                        else {
                            _events.emit(ServerMessage(cmd.playerId, "Ты отошел слишком далеко от Алхимика"))
                            return
                        }

                    }
                    "give_herb" -> {
                        if (player.questState != QuestState.WAIT_HERB) {
                            _events.emit(ServerMessage(cmd.playerId, "Сейчас нельзя сдать траву"))
                        }

                        val herbs = herbCount(player)

                        if (herbs < 3) {
                            _events.emit(ServerMessage(cmd.playerId, "Недостаточно травы"))
                            return
                        }

                        val newCount = herbs - 3
                        val newInventory =
                            if (newCount <= 0) player.inventory - "herb" else player.inventory + ("herb" to newCount)

                        val newMemory = player.alchemistMemory.copy(
                            receivedHerb = true
                        )

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(
                                inventory = newInventory,
                                gold = p.gold + 5,
                                questState = QuestState.GOOD_END,
                                alchemistMemory = newMemory
                            )
                        }
                        _treasureChestVisible.value = true

                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.GOOD_END))
                        _events.emit(ServerMessage(cmd.playerId, "Алхимик получил траву и выдал тебе золото"))
                    }

                    else -> {
                        _events.emit(ServerMessage(cmd.playerId, "Неизвестный формат диалога"))
                    }
                }
            }

            is CmdResetPlayer -> {
                updatePlayer(cmd.playerId) { _ -> initialPlayerState(cmd.playerId)}
                _events.emit(ServerMessage(cmd.playerId, "Игрок сброшен к начальному уровню"))
            }
        }
    }

    private fun startRewardCutscene(playerId: String){
        val scope = serverScope ?: return

        // если катсцена идет, то вторую не запускаем
        if (cutsceneJobs[playerId]?.isActive == true){
            scope.launch {
                _events.emit(ServerMessage(playerId, "Катсцена уже запущенна"))
            }
            return
        }

        val job = scope.launch {
            // блокируем управление игроком при старте катсцены
            updatePlayer(playerId){ p ->
                p.copy(
                    inputLocked = true,
                    cutsceneActive = true,
                    cutsceneText = "Алхимик варит траву"
                )
            }

            _events.emit(CutSceneStarted(playerId, "alchemist_reward"))
            _events.emit(CutSceneStep(playerId, "Алхимик варит траву"))

            delay(1200)

            val p1 = getPlayerData(playerId)
            val herbs = herbCount(p1)
            val newCount = herbs - 3

            val newInventory =
                if (newCount <= 0) p1.inventory  - "herb" else p1.inventory + ("herb" to newCount)

            val newMemory = p1.alchemistMemory.copy(
                receivedHerb = true
            )

            updatePlayer(playerId){ p ->
                p.copy(
                    inventory = newInventory,
                    alchemistMemory = newMemory,
                    cutsceneText = "Алхимик смешивает ингридиенты"
                )
            }

            _events.emit(InventoryChanged(playerId, "herb", newCount))
            _events.emit(NpcMemoryChanged(playerId, newMemory))
            _events.emit(CutSceneStep(playerId, "Алхимик смешивает ингредиенты"))

            delay(1200)

            updatePlayer(playerId) {p ->
                p.copy(
                    gold = p.gold + 5,
                    questState = QuestState.GOOD_END,
                    cutsceneText = "Алхимик дает тебе награду"
                )
            }

            _events.emit(QuestStateChanged(playerId, QuestState.GOOD_END))
            _events.emit(CutSceneStep(playerId, "Алхимик дает тебе награду"))

            delay(900)

            updatePlayer(playerId){ p ->
                p.copy(
                    inputLocked = false,
                    cutsceneActive = false,
                    cutsceneText = "",
                    hintText = "Катсцена завершена"
                )
            }

            _events.emit(CutSceneFinished(playerId, "alchemist_reward"))
            _events.emit(ServerMessage(playerId, "Катсцена завершена и возращено управление"))
        }

        cutsceneJobs[playerId] = job
    }
}

class HudState{
    val activePlayerIdFlow = MutableStateFlow("Oleg")

    val activePLayerIdUi = mutableStateOf("Oleg")

    val playerSnapShot = mutableStateOf(initialPlayerState("Oleg"))

    val log = mutableStateOf<List<String>>(emptyList())
}

fun hudLog(hud: HudState, line: String){
    hud.log.value = (hud.log.value + line).takeLast(20)
}

fun formatInventory(player: PlayerState) : String{
    return if(player.inventory.isEmpty()){
        "Inventory: (пусто)"
    }else{
        "Inventory " + player.inventory.entries.joinToString { "${it.key} x${it.value}" }
    }
}

fun currentObjective(player: PlayerState) : String{
    val herbs = herbCount(player)

    return when(player.questState){
        QuestState.START -> "Подойди к алхимику и начни разговор"
        QuestState.WAIT_HERB -> {
            if (herbs < 3) "Собери 3 травы. Сейчас $herbs / 3"
            else "Вернись к алхимику и отдай 3 травы"
        }

        QuestState.GOOD_END -> "Квест завершен по хорошей ветке"
        QuestState.EVIL_END -> "Квест завершен по плохой ветке"
    }
}

fun currentZoneText(player: PlayerState): String{
    return when(player.currentAreaId){
        "alchemist" -> "Зона: Алхимик"
        "herb_source" -> "Зона источника травы"
        "treasure_box" -> "Зона сундука"
        else -> "Без зоны :("
    }
}

fun formatMemory(memory: NpcMemory): String{
    return "Встретился = ${memory.hasMet}, Сколько раз поговорил = ${memory.timesTalked}, отдал траву = ${memory.receivedHerb}"
}

fun eventToText(e: GameEvent): String{
    return when(e){
        is EnteredArea -> "EnteredArea ${e.areaId}"
        is LeftArea -> "LeftArea ${e.areaId}"
        is InteractedWithNpc -> "InteractedWithNpc ${e.npcId}"
        is InteractedWithHerbSource -> "InteractedWithHerbSource ${e.sourceId}"
        is InventoryChanged -> "InventoryChanged ${e.itemId} -> ${e.newCount}"
        is QuestStateChanged -> "QuestStateChanged ${e.newState}"
        is NpcMemoryChanged -> "NpcMemoryChanged Встретился = ${e.memory.hasMet}, Сколько раз поговорил = ${e.memory.timesTalked}, отдал траву = ${e.memory.receivedHerb}"
        is ServerMessage -> "Server: ${e.text}"
        is CutSceneStep -> "CutSceneStep ${e.text}"
        is CutSceneStarted -> "CutSceneStarted ${e.cutsceneId}"
        is CutSceneFinished -> "CutSceneFinished ${e.cutsceneId}"
        else -> ""
    }
}
fun main() = KoolApplication {
    val hud = HudState()
    val server = GameServer()

    addScene {
        defaultOrbitCamera()

        val playerNode = addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }

        val alchemistNode = addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }

        alchemistNode.transform.translate(3f, 0f, 0f)

        val herbNode = addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }


        herbNode.transform.translate(3f, 0f, 0f)

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }

        server.start(coroutineScope)

        var lastRenderedX = 0f
        var lastRenderedZ = 0f

        playerNode.onUpdate {
            val activeId = hud.activePlayerIdFlow.value
            val player = server.getPlayerData(activeId)

            val dx = player.posX - lastRenderedX
            val dz = player.posZ - lastRenderedZ

            playerNode.transform.translate(dx, 0f, dz)

            lastRenderedX = player.posX
            lastRenderedZ = player.posZ
        }

        alchemistNode.onUpdate {
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
        herbNode.onUpdate {
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
    }
}