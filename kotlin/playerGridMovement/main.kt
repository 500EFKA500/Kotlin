package playerGridMovement

import de.fabmax.kool.KoolApplication           // KoolApplication - запускает Kool-приложение (окно + цикл рендера)
import de.fabmax.kool.addScene                  // addScene - функция "добавь сцену" в приложение (у тебя она просила отдельный импорт)
import de.fabmax.kool.math.Vec3f                // Vec3f - 3D-вектор (x, y, z), как координаты / направление
import de.fabmax.kool.math.deg                  // deg - превращает число в "градусы" (угол)
import de.fabmax.kool.scene.*                   // scene.* - Scene, defaultOrbitCamera, addColorMesh, lighting и т.д.
import de.fabmax.kool.modules.ksl.KslPbrShader  // KslPbrShader - готовый PBR-шейдер (материал)
import de.fabmax.kool.util.Color                // Color - цвет (RGBA)
import de.fabmax.kool.util.Time                 // Time.deltaT - сколько секунд прошло между кадрами
import de.fabmax.kool.pipeline.ClearColorLoad   // ClearColorLoad - режим: "не очищай экран, оставь то что уже нарисовано"
import de.fabmax.kool.modules.ui2.*             // UI2: addPanelSurface, Column, Row, Button, Text, dp, remember, mutableStateOf
import kotlinx.coroutines.CoroutineScope

import kotlinx.coroutines.launch                    // запуск корутин
import kotlinx.coroutines.Job

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
import kotlin.math.sqrt

enum class QuestState{
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END
}

enum class Facing{
    LEFT,
    RIGHT,
    FORWARD,
    BACK
}

// типы объектов
enum class WorldObjectType{
    ALCHEMIST,
    HERB_SOURCE,
    CHEST
}

data class GridPos(
    val x: Int,
    val z: Int
)

// описание объектов в игровом мире
data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val cellX: Float,
    val cellZ: Float,
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
    val gridX: Int,
    val gridZ: Int,
    val questState: QuestState,
    val inventory: Map<String, Int>,
    val alchemistMemory: NpcMemory,
    val currentAreaId: String?,
    val hintText: String,
    val gold: Int,
    val facing: Facing
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

fun facingToYawDeg(facing: Facing): Float{
    //
    //
    return when(facing){
        Facing.FORWARD -> 0f
        Facing.RIGHT -> 90f
        Facing.BACK -> 180f
        Facing.LEFT -> 270f
    }
}

fun lerp(current: Float, target: Float, t: Float): Float{
    // линейная интерполяция
    // нужна для плавного перемещения current в сторону target
    // формула - current + (target - current) * t
    return current + (target - current) * t
}

fun initialPlayerState(playerId: String): PlayerState {
    return if(playerId == "Stas"){
        PlayerState(
            "Stas",
            0,
            0,
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
            Facing.FORWARD
        )
    }else{
        PlayerState(
            "Oleg",
            0,
            0,
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
            Facing.FORWARD
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

fun buildAlchemistDialogue(player:  PlayerState):  DialogueView {
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

data class CmdStepMove(
    override val playerId: String,
    val stepX: Int,
    val stepZ: Int
): GameCommand

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

data class PlayerMoved(
    override val playerId: String,
    val newGridX: Int,
    val newGridZ: Int
): GameEvent

data class MovedBlocked(
    override val playerId: String,
    val blockedX: Int,
    val blockedZ: Int
): GameEvent

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

    // размер карты

    private val minX = -5
    private val maxX = 5
    private val minZ = -4
    private val maxZ = 4

    // подготовка клеток на которые нельзя зайти
    private val blockedCells = setOf(
        GridPos(-1, 1),
        GridPos(0, 1),
        GridPos(1, 1),
        GridPos(1, 0),
        GridPos(-2, 0)
    )

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
                GameCommand
    ): Boolean = _commands.tryEmit(cmd)

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

    fun start(scope: CoroutineScope) {
        scope.launch {
            commands.collect { cmd ->
                processCommand(cmd)
            }
        }
    }

    fun setPlayerData(playerId: String, data:
    PlayerState
    ) {
        val map = _players.value.toMutableMap()
        map[playerId] = data
        _players.value = map.toMap()
    }

    fun getPlayerData(playerId: String):
            PlayerState {
        return _players.value[playerId] ?: initialPlayerState(playerId)
    }

    private fun updatePlayer(playerId: String, change: (PlayerState) -> PlayerState) {
        val oldMap = _players.value
        val oldPlayer = oldMap[playerId] ?: return

        val newPlayer = change(oldPlayer)

        val newMap = oldMap.toMutableMap()
        newMap[playerId] = newPlayer
        _players.value = newMap.toMap()
    }

    private fun isCellInsideMap(x: Int, z: Int): Boolean{
        // находится ли клетка для перемещения в допустимой клетке
        return x in minX .. maxX && z in minZ .. maxZ
        // x in minX..maxX - "входит в диапазон"
    }

    private fun isCellBlocked(x: Int, z: Int): Boolean{
        // проверка, запрещена ли клетка для входа в нее
        return GridPos(x, z) in blockedCells
    }

    // cutsceneJobs[playerId] = текущая катсцена этого игрока
    private val cutsceneJobs = mutableMapOf<String, Job>()

    private var serverScope: CoroutineScope? = null



    private fun nearestObject(player:  PlayerState): WorldObjectDef?{
        val px = player.gridX.toFloat()
        val pz = player.gridZ.toFloat()

        val candidates = worldObjects.filter { obj ->
            distance2D(px, pz, obj.cellX, obj.cellZ) <= obj.interactRadius
        }

        return candidates.minByOrNull { obj ->
            distance2D(px, pz, obj.cellX, obj.cellZ)
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

    private suspend fun processCommand(cmd:  GameCommand){
        when(cmd){
            is CmdStepMove -> {
                val player = getPlayerData(cmd.playerId)
                val targetX = player.gridX + cmd.stepX
                val targetZ = player.gridZ + cmd.stepZ

                val newFacing =
                    when{
                        cmd.stepX < 0 -> Facing.LEFT
                        cmd.stepX > 0 -> Facing.RIGHT
                        cmd.stepZ < 0 -> Facing.FORWARD
                        else -> Facing.BACK
                    }
                if (!isCellInsideMap(targetX, targetZ)){
                    _events.emit(ServerMessage(cmd.playerId, "нельзя уйти из зоны"))
                    _events.emit(MovedBlocked(cmd.playerId, targetX, targetZ))

                    updatePlayer(cmd.playerId){ p ->
                        p.copy(facing = newFacing)
                    }
                    return
                }

                if (isCellBlocked(targetX, targetZ)){
                    _events.emit(ServerMessage(cmd.playerId, "нельзя уйти из зоны"))
                    _events.emit(MovedBlocked(cmd.playerId, targetX, targetZ))

                    updatePlayer(cmd.playerId){ p ->
                        p.copy(facing = newFacing)
                    }
                    return
                }

                updatePlayer(cmd.playerId){ p ->
                    p.copy(
                        gridX = targetX,
                        gridZ = targetZ,
                        facing = newFacing
                    )
                }

                _events.emit(PlayerMoved(cmd.playerId, targetX, targetZ))

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
                        if (player.questState !=  QuestState.WAIT_HERB){
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

                        if (player.questState !=  QuestState.GOOD_END) {
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
                        val radiusHerb = distance2D(player.gridX.toFloat(), player.gridZ.toFloat(), 3f, 0f)
                        if (radiusHerb <= 1.7f){
                            if (player.questState !=  QuestState.START){
                                _events.emit(ServerMessage(cmd.playerId, "Путь помощи можно выбрать только в начале квеста"))
                                return
                            }

                            updatePlayer(cmd.playerId){ p ->
                                p.copy(questState =  QuestState.WAIT_HERB)
                            }

                            _events.emit(QuestStateChanged(cmd.playerId,  QuestState.WAIT_HERB))
                            _events.emit(ServerMessage(cmd.playerId, "Алхимик просит собрать х3 травы"))
                        }
                        else {
                            _events.emit(ServerMessage(cmd.playerId, "Ты отошел слишком далеко от Алхимика"))
                            return
                        }

                    }
                    "give_herb" -> {
                        if (player.questState !=  QuestState.WAIT_HERB) {
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
                                questState =  QuestState.GOOD_END,
                                alchemistMemory = newMemory
                            )
                        }
                        _treasureChestVisible.value = true

                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                        _events.emit(QuestStateChanged(cmd.playerId,  QuestState.GOOD_END))
                        _events.emit(ServerMessage(cmd.playerId, "Алхимик получил траву и выдал тебе золото"))
                    }

                    else -> {
                        _events.emit(ServerMessage(cmd.playerId, "Неизвестный формат диалога"))
                    }
                }
            }

            is CmdResetPlayer -> {
                updatePlayer(cmd.playerId) { _ -> initialPlayerState(cmd.playerId) }
                _events.emit(ServerMessage(cmd.playerId, "Игрок сброшен к начальному уровню"))
            }
        }
    }
}

class HudState{
    val activePlayerIdFlow = MutableStateFlow("Oleg")

    val activePlayerIdUi = mutableStateOf("Oleg")

    val playerSnapShot = mutableStateOf(initialPlayerState("Oleg"))

    val log = mutableStateOf<List<String>>(emptyList())
}

fun hudLog(hud: HudState, line: String){
    hud.log.value = (hud.log.value + line).takeLast(20)
}

fun formatInventory(player:  PlayerState): String{
    return if (player.inventory.isEmpty()){
        "Inventory: пусто"
    }else{
        "Inventory: " + player.inventory.entries.joinToString { "${it.key} x${it.value}" }
    }
}

fun currentObjective(player:  PlayerState): String{
    val herbs = herbCount(player)

    return when (player.questState){
        QuestState.START -> "Подойди к алхимику и начни разговор"
        QuestState.WAIT_HERB -> {
            if (herbs < 3) "Собери х3 травы. Сейчас $herbs / 3"
            else "Вернись к алхимику и отдай 3 травы"
        }

        QuestState.GOOD_END -> "Квест завершен по хорошей ветке"
        QuestState.EVIL_END -> "Квест завершен по плохой ветке"
    }
}

fun currentZoneText(player:  PlayerState): String{
    return when(player.currentAreaId){
        "alchemist" -> "Зона: Алхимик"
        "herb_source" -> "Зона Источника травы"
        else -> " открытое пространство"
    }
}

fun formatMemory(memory:  NpcMemory): String{
    return "Встретился: ${memory.hasMet}, сколько раз поговорил: ${memory.timesTalked}, отдал траву: ${memory.receivedHerb}"
}

fun eventToText(e:  GameEvent): String{
    return when(e){
        is PlayerMoved -> "playerGridMovement.PlayerMoved (${e.newGridX}, ${e.newGridZ})"
        is MovedBlocked -> "playerGridMovement.MovedBlocked (${e.blockedX}, ${e.blockedZ})"
        is  EnteredArea -> "playerGridMovement.EnteredArea ${e.areaId}"
        is  LeftArea -> "playerGridMovement.LeftArea ${e.areaId}"
        is  InteractedWithNpc -> "playerGridMovement.InteractedWithNpc ${e.npcId}"
        is  InteractedWithHerbSource -> "playerGridMovement.InteractedWithHerbSource ${e.sourceId}"
        is  InventoryChanged -> "playerGridMovement.InventoryChanged ${e.itemId} -> ${e.newCount}"
        is  QuestStateChanged -> "playerGridMovement.QuestStateChanged ${e.newState}"
        is  NpcMemoryChanged -> "playerGridMovement.NpcMemoryChanged Встретился: ${e.memory.hasMet}, сколько раз поговорил: ${e.memory.timesTalked}, отдал траву: ${e.memory.receivedHerb}"
        is  ServerMessage -> "Server: ${e.text}"
        else -> ""
    }
}

fun main() = KoolApplication {
    val hud = HudState()
    val server = GameServer()

    addScene {
        defaultOrbitCamera()

        // Строим пол из мелких кубиков
        for (x in -5..5){
            for (z in -4..4){
                addColorMesh {
                    generate { cube{colored()} }

                    shader = KslPbrShader{
                        color{vertexColor()}
                        metallic(0f)
                        roughness(0.25f)
                    }
                }
                    .transform.translate(x.toFloat(), -1.2f,  z.toFloat())
                // Сдвигаем плитку (кубы - пол) в мире
                // y = -1.2f опускаем пол ниже игрока
            }
        }

        val wallCells = listOf(
            GridPos(-1, 1),
            GridPos(0, 1),
            GridPos(1, 1),
            GridPos(1, 0),
            GridPos(-2, 0)
        )

        for (cell in wallCells){
            addColorMesh {
                generate { cube{colored()} }

                shader = KslPbrShader{
                    color{vertexColor()}
                    metallic(0f)
                    roughness(0.25f)
                }
            }
                .transform.translate(cell.x.toFloat(), -1.2f,  cell.z.toFloat())
        }

        val playerNode = addColorMesh {
            generate {
                cube{
                    colored()
                }
            }
            shader = KslPbrShader{
                color{vertexColor()}
                metallic(0f)
                roughness(0.25f)
            }
        }

        val alchemistNode = addColorMesh {
            generate {
                cube{
                    colored()
                }
            }
            shader = KslPbrShader{
                color{vertexColor()}
                metallic(0f)
                roughness(0.25f)
            }
        }

        alchemistNode.transform.translate(3f,0f,0f)

        val herbNode = addColorMesh {
            generate {
                cube{
                    colored()
                }
            }
            shader = KslPbrShader{
                color{vertexColor()}
                metallic(0f)
                roughness(0.25f)
            }
        }


        herbNode.transform.translate(3f,0f,0f)

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f,-1f,-1f))
            setColor(Color.WHITE, 5f)
        }

        server.start(coroutineScope)

        var renderX = 0f
        var renderZ = 0f
        var lastAppliedX = 0f
        var lastAppliedZ = 0f

        var lastAppliedYaw = 0f
        // yaw - какой поворот уже был применен к PlayerNode

        playerNode.onUpdate{
            val activeId = hud.activePlayerIdFlow.value
            val player = server.getPlayerData(activeId)

            val targetX = player.gridX.toFloat()
            val targetZ = player.gridZ.toFloat()

            // Плавность перемещения
            // чем больше коэффицент, тем быстрее куб переходит на новую клетку
            val speed = Time.deltaT * 8f
            val t = if(speed > 1f) 1f else speed

            renderX = lerp(renderX, targetX, t)
            renderZ = lerp(renderZ, targetZ, t)

            val dx = renderX - lastAppliedX
            val dz = renderZ - lastAppliedZ

            playerNode.transform.translate(dx, 0f, dz)

            lastAppliedX = renderX
            lastAppliedZ = renderZ

            // Поварачиваем игрока по направлению
            val targetYaw = facingToYawDeg(player.facing)
            val yawDelta = targetYaw - lastAppliedYaw

            playerNode.transform.rotate(yawDelta.deg, Vec3f.Y_AXIS)

            lastAppliedYaw = targetYaw
        }
    }

    addScene {
        setupUiScene(ClearColorLoad)

        hud.activePlayerIdFlow
            .flatMapLatest { pid ->
                server.players.map { map ->
                    map[pid] ?: initialPlayerState(pid)
                }
            }
            .onEach { player ->
                hud.playerSnapShot.value = player
            }
            .launchIn(coroutineScope)
        hud.activePlayerIdFlow
            .flatMapLatest { pid ->
                server.events.filter { it.playerId == pid }
            }
            .map{ event ->
                eventToText(event)
            }
            .onEach { line ->
                hudLog(hud, "[${hud.activePlayerIdUi.value}] $line")
            }
            .launchIn(coroutineScope)

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))
                .padding(12.dp)

            Column {
                val player = hud.playerSnapShot.use()
                val dialogue = buildAlchemistDialogue(player)

                Text("Игрок: ${hud.activePlayerIdUi.use()}"){ modifier.margin(bottom = sizes.gap) }
                Text("Позиция: x=${player.gridX} z=${player.gridZ}"){}
                Text("Смотрит: ${player.facing}"){modifier.font(sizes.smallText).margin(bottom = sizes.smallGap)}
                Text("Quest State: ${player.questState}"){ modifier.font(sizes.smallText) }
                Text(currentObjective(player)){ modifier.font(sizes.smallText) }
                Text(formatInventory(player)){ modifier.font(sizes.smallText).margin(bottom = sizes.smallGap) }
                Text("Gold: ${player.gold}"){ modifier.font(sizes.smallText) }
                Text("Hint: ${player.hintText}"){ modifier.font(sizes.smallText) }
                Text("Npc Memory: ${formatMemory(player.alchemistMemory)}"){ modifier.font(sizes.smallText).margin(bottom = sizes.smallGap) }

                Row {
                    Button("Сменить игрока"){
                        modifier.margin(end = 8.dp).onClick{
                            val newId = if(hud.activePlayerIdUi.value == "Oleg") "Stas" else "Oleg"

                            hud.activePlayerIdUi.value = newId
                            hud.activePlayerIdFlow.value = newId
                        }
                    }
                    Button("Сбросить игрока"){
                        modifier.onClick{
                            server.trySend(CmdResetPlayer(player.playerId))
                        }
                    }
                }

                Text("Движение в мире:"){ modifier.margin(top = sizes.gap) }

                Row {
                    Button("Лево"){
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdStepMove(player.playerId, stepX = -1, stepZ = 0))
                        }
                    }
                    Button("Право"){
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdStepMove(player.playerId, stepX = 1, stepZ = 0))
                        }
                    }
                    Button("Вперед"){
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdStepMove(player.playerId, stepX = 0, stepZ = -1))
                        }
                    }
                    Button("Назад"){
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdStepMove(player.playerId, stepX = 0, stepZ = -1))
                        }
                    }
                    Button("Dash Forward") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdStepMove(player.playerId, stepX = 0, stepZ = -1))
                            server.trySend(CmdStepMove(player.playerId, stepX = 0, stepZ = -1))
                        }
                    }
                }
                Text("Взаимодействия:"){ modifier.margin(top = sizes.gap) }

                Row {
                    Button("Потрогать ближайшего"){
                        modifier.margin(end = 8.dp).onClick{
                            server.trySend(CmdInteract(player.playerId))
                        }
                    }
                }

                Text(dialogue.npcId){ modifier.margin(top = sizes.gap) }
                Text(dialogue.text){ modifier.margin(bottom = sizes.smallGap) }

                if(dialogue.option.isEmpty()){
                    Text("Нет доступных варинатов ответа"){
                        modifier.font(sizes.smallText).margin(bottom = sizes.gap)
                    }
                }else{
                    Row{
                        for (option in dialogue.option){
                            Button(option.text){
                                modifier.margin(end = 8.dp).onClick{
                                    server.trySend(
                                        CmdChooseDialogueOption(
                                            player.playerId,
                                            option.id
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                Text("Лог: "){modifier.margin(top = sizes.gap)}

                for(line in hud.log.use()){
                    Text(line){ modifier.font(sizes.smallText) }
                }
            }
        }
    }
}
