package playerGridMovement

import de.fabmax.kool.KoolApplication           // KoolApplication - запускает Kool-приложение (окно + цикл рендера)
import de.fabmax.kool.addScene                  // addScene - функция "добавь сцену" в приложение (у тебя она просила отдельный импорт)
import de.fabmax.kool.math.Vec2f
import de.fabmax.kool.math.Vec3f                // Vec3f - 3D-вектор (x, y, z), как координаты / направление
import de.fabmax.kool.math.deg                  // deg - превращает число в "градусы" (угол)
import de.fabmax.kool.modules.audio.synth.SampleNode
import de.fabmax.kool.scene.*                   // scene.* - Scene, defaultOrbitCamera, addColorMesh, lighting и т.д.
import de.fabmax.kool.modules.ksl.KslPbrShader  // KslPbrShader - готовый PBR-шейдер (материал)
import de.fabmax.kool.pipeline.*
import de.fabmax.kool.platform.ImageDecoder
import de.fabmax.kool.util.Color                // Color - цвет (RGBA)
import de.fabmax.kool.util.Time                 // Time.deltaT - сколько секунд прошло между кадрами
import de.fabmax.kool.pipeline.ClearColorLoad   // ClearColorLoad - режим: "не очищай экран, оставь то что уже нарисовано"
import de.fabmax.kool.modules.ui2.*             // UI2: addPanelSurface, Column, Row, Button, Text, dp, remember, mutableStateOf
import de.fabmax.kool.physics.joints.DistanceJoint
import de.fabmax.kool.scene.geometry.GridProps
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
import javax.accessibility.AccessibleValue
import javax.management.ValueExp
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent
import kotlin.concurrent.thread
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

enum class Facing{
    LEFT,
    RIGHT,
    FORWARD,
    BACK
}

data class GridPos(
    val x: Int,
    val z: Int
)

// описание объектов в игровом мире
data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val cellX: Int,
    val cellZ: Int,
    val interactRadius: Float
)

data class NpcMemory(
    val hasMet: Boolean,
    val timesTalked: Int,
    val receivedHerb: Boolean,
    val sawPlayerNearSource: Boolean = false
)

fun herbCount(player: PlayerState): Int{
    return player.inventory["herb"] ?: 0
}

fun facingToYawDeg(facing: Facing): Float{
    // Превращаем направление в угол поворота по оси Y
    // Нужно для визуального отображения поворота куба
    return when(facing){
        Facing.FORWARD -> 0f
        Facing.RIGHT -> 90f
        Facing.BACK -> 180f
        Facing.LEFT -> 270F
    }
}

fun lerp(current: Float, target: Float, t: Float): Float{
    // линейная интерполяция
    // Простыми словами нужны для плавного премещения current в сторону target
    // Формула = current + (target - current) * t
    return current + (target - current) * t
}

//d = √((x₂ - x₁)² + (y₂ - y₁)²)
fun distance2D(ax: Float, az: Float, bx: Float, bz: Float): Float{
    val dx = ax - bx
    val dz = az - bz
    return sqrt(dx*dx + dz*dz)
}

data class PlayerState(
    val playerId: String,
    val gridX: Int,
    val gridZ: Int,
    val rotate: Float,
    val questState: QuestState,
    val inventory: Map<String, Int>,
    val gold: Int,

    val alchemistMemory: NpcMemory,
    val currentAreaId: String?,
    val hintText: String,

    val facing: Facing
)

fun initialPlayerState(playerId: String): PlayerState {
    return if(playerId == "Stas"){
        PlayerState(
            "Stas",
            0,
            0,
            0f,
            QuestState.START,
            emptyMap(),
            0,
            NpcMemory(
                true,
                2,
                false
            ),
            null,
            "Подойди к одной из локаций",
            Facing.FORWARD
        )
    }else{
        PlayerState(
            "Oleg",
            0,
            0,
            0f,
            QuestState.START,
            emptyMap(),
            0,
            NpcMemory(
                true,
                2,
                false
            ),
            null,
            "Подойди к одной из локаций",
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

fun buildAlchemistDialogue(player: PlayerState): DialogueView{

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

data class CmdRotate(
    override val playerId: String,
    val rotate: Float
): GameCommand

data class CmdInteract(
    override val playerId: String
): GameCommand

data class CmdChooseDialogueOption(
    override val playerId: String,
    val optionId: String
): GameCommand

data class CmdSwitchActivePlayer(
    override val playerId: String,
    val newPlayerId: String
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

data class InventoryChanged(
    override val playerId: String,
    val itemId: String,
    val newCount: Int
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val newState: QuestState
): GameEvent

data class NpcMemoryChanged(
    override val playerId: String,
    val memory: NpcMemory
): GameEvent

data class ServerMessage(
    override val playerId: String,
    val text: String
): GameEvent

data class InteractedWithChest(
    override val playerId: String,
    val chestId: String
): GameEvent

data class GoldCountChanged(
    override val playerId: String,
    val newCount: Int
): GameEvent

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

data class Win(
    override val playerId: String
): GameEvent

class GameServer {

    // Размер карты, игрок может ходить только в ее пределах

    private val minX = -5
    private val maxX = 5
    private val minZ = -4
    private val maxZ = 4

    // Подготовка клеток, на которые нельзя зайти (занятые)
    private val blockedCells = setOf(
        GridPos(-1, 1),
        GridPos(0, 1),
        GridPos(1, 1),
        GridPos(1, 0)
    )

    val worldObjects = mutableListOf(
        WorldObjectDef(
            "alchemist",
            WorldObjectType.ALCHEMIST,
            -3,
            0,
            1f
        ),
        WorldObjectDef(
            "herb_source",
            WorldObjectType.HERB_SOURCE,
            3,
            0,
            1f
        ),
        WorldObjectDef(
            "treasure_box",
            WorldObjectType.CHEST,
            5,
            0,
            2f
        )
    )

    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _commands = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<GameCommand> = _commands.asSharedFlow()

    fun trySend(cmd: GameCommand): Boolean = _commands.tryEmit(cmd)

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to initialPlayerState("Oleg"),
            "Stas" to initialPlayerState("Stas")
        )
    )

    val players: StateFlow<Map<String, PlayerState>> = _players.asStateFlow()
    private val _treasureChestVisible = MutableStateFlow(false)

    fun start(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            commands.collect { cmd ->
                processCommand(cmd)
            }
        }
    }

    private fun setPlayerData(playerId: String, data: PlayerState) {
        val map = _players.value.toMutableMap()
        map[playerId] = data
        _players.value = map.toMap()
    }

    fun getPlayerData(playerId: String): PlayerState {
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
        // Находится ли клетка для перемещения в допустимой карте
        return x in minX..maxX && z in minZ..maxZ
        // х im minX..maxX - "х входит в диапазон от minX до maxX"
    }

    private fun isCellBlocked(x: Int, z: Int): Boolean{
        // проверка, запрещена ли клетка для входа в нёё
        return GridPos(x, z) in blockedCells
    }

    private fun nearestObject(player: PlayerState): WorldObjectDef? {
        val px = player.gridX.toFloat()
        val pz = player.gridX.toFloat()

        val candidates = worldObjects.filter { obj ->
            distance2D(px, pz, obj.cellX.toFloat(), obj.cellZ.toFloat()) <= obj.interactRadius
        }

        return candidates.minByOrNull { obj ->
            distance2D(px, pz, obj.cellX.toFloat(), obj.cellZ.toFloat())
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
                    "treasure_box" -> "открыть сундук"
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

    private fun randomChance(probability: Float): Boolean {
        return kotlin.random.Random.nextFloat() < probability
    }

    private suspend fun processCommand(cmd: GameCommand){
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
                    _events.emit(ServerMessage(cmd.playerId, "Нельзя уйти за границы карты"))
                    _events.emit(MovedBlocked(cmd.playerId, targetX, targetZ))

                    updatePlayer(cmd.playerId){ p ->
                        p.copy(facing = newFacing)
                    }
                    return
                }
                if (isCellBlocked(targetX, targetZ)){
                    _events.emit(ServerMessage(cmd.playerId, "Путь заблокирован стеной"))
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

            is CmdRotate -> {
                val player = getPlayerData(cmd.playerId)
                val targetRotate = player.rotate + cmd.rotate

                updatePlayer(cmd.playerId){ p ->
                    p.copy(
                        rotate = targetRotate
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
                        val radiusHerb = distance2D(player.gridX.toFloat(), player.gridZ.toFloat(), 3f, 0f)
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
                        _events.emit(Win(cmd.playerId))
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

            is CmdSwitchActivePlayer -> {
                if (_players.value.containsKey(cmd.newPlayerId)) {
                    _events.emit(ServerMessage(cmd.playerId, "Активный игрок переключен на ${cmd.newPlayerId}"))
                } else {
                    _events.emit(ServerMessage(cmd.playerId, "Игрок ${cmd.newPlayerId} не найден"))
                }
            }
        }
    }
}

class HudState{
    val activePlayerIdFlow = MutableStateFlow("Oleg")

    val activePLayerIdUi = mutableStateOf("Oleg")

    val playerSnapShot = mutableStateOf(initialPlayerState("Oleg"))

    val log = mutableStateOf<List<String>>(emptyList())

    val isWinVisible = mutableStateOf(false)
    val winGifProvider = mutableStateOf<ImageProvider?>(null)
    val hasPlayedWinSound = mutableStateOf(false)
}

private data class GifFrame(
    val imageData: BufferedImageData2d,
    val delayMs: Int
)

private class AnimatedGifImageProvider(
    private val frames: List<GifFrame>,
    private val texture: Texture2d
) : ImageProvider {
    override val uvTopLeft = Vec2f(0f, 0f)
    override val uvTopRight = Vec2f(1f, 0f)
    override val uvBottomLeft = Vec2f(0f, 1f)
    override val uvBottomRight = Vec2f(1f, 1f)

    override val isDynamicSize: Boolean = true

    private val loopDurationMs = frames.sumOf { it.delayMs.coerceAtLeast(16) }.coerceAtLeast(16)
    private var currentFrame = -1
    private var startTimeSec = Time.gameTime

    override fun getTexture(imgWidthPx: Float, imgHeightPx: Float): Texture2d {
        val elapsedMs = ((Time.gameTime - startTimeSec) * 1000.0).toInt()
        val loopTimeMs = ((elapsedMs % loopDurationMs) + loopDurationMs) % loopDurationMs

        var frameIndex = 0
        var acc = 0
        for (i in frames.indices) {
            acc += frames[i].delayMs.coerceAtLeast(16)
            if (loopTimeMs < acc) {
                frameIndex = i
                break
            }
        }

        if (frameIndex != currentFrame) {
            texture.uploadLazy(frames[frameIndex].imageData)
            currentFrame = frameIndex
        }
        return texture
    }

    companion object {
        fun fromPath(path: String): AnimatedGifImageProvider? {
            val file = File(path)
            if (!file.exists()) return null

            val frames = decodeGifFrames(file)
            if (frames.isEmpty()) return null

            val texture = Texture2d(
                data = frames.first().imageData,
                mipMapping = MipMapping.Off,
                samplerSettings = SamplerSettings(maxAnisotropy = 1),
                name = "win-gif-texture"
            )
            return AnimatedGifImageProvider(frames, texture)
        }

        private fun decodeGifFrames(file: File): List<GifFrame> {
            val result = mutableListOf<GifFrame>()
            val reader = ImageIO.getImageReadersByFormatName("gif").asSequence().firstOrNull() ?: return emptyList()
            ImageIO.createImageInputStream(file).use { input ->
                if (input == null) return emptyList()
                reader.input = input
                val frameCount = reader.getNumImages(true)
                for (i in 0 until frameCount) {
                    val buffered = reader.read(i)
                    val metadata = reader.getImageMetadata(i)
                    val root = metadata.getAsTree("javax_imageio_gif_image_1.0") as? IIOMetadataNode
                    val gce = root?.getElementsByTagName("GraphicControlExtension")?.item(0) as? IIOMetadataNode
                    val delayCs = gce?.getAttribute("delayTime")?.toIntOrNull() ?: 6
                    val imageData = ImageDecoder.loadBufferedImage(buffered, TexFormat.RGBA)
                    result += GifFrame(imageData, delayCs * 10)
                }
            }
            reader.dispose()
            return result
        }
    }
}

private fun playWinSoundIfExists(hud: HudState) {
    if (hud.hasPlayedWinSound.value) return

    val soundFile = listOf(
        File("kotlin/playerGridMovement/victory.mp3")
    ).firstOrNull { it.exists() } ?: return

    hud.hasPlayedWinSound.value = true

    thread(isDaemon = true, name = "win-sound-player") {
        runCatching {
            AudioSystem.getAudioInputStream(soundFile).use { input ->
                val clip = AudioSystem.getClip()
                clip.addLineListener { event ->
                    if (event.type == LineEvent.Type.STOP) {
                        clip.close()
                    }
                }
                clip.open(input)
                clip.start()
            }
        }.onFailure {
            hud.hasPlayedWinSound.value = false
        }
    }
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
        is PlayerMoved -> "PlayerMoved (${e.newGridX}, ${e.newGridZ})"
        is MovedBlocked -> "Moved Blocked (${e.blockedX}, ${e.blockedZ})"
        is EnteredArea -> "EnteredArea ${e.areaId}"
        is LeftArea -> "LeftArea ${e.areaId}"
        is InteractedWithNpc -> "InteractedWithNpc ${e.npcId}"
        is InteractedWithHerbSource -> "InteractedWithHerbSource ${e.sourceId}"
        is InventoryChanged -> "InventoryChanged ${e.itemId} -> ${e.newCount}"
        is QuestStateChanged -> "QuestStateChanged ${e.newState}"
        is NpcMemoryChanged -> "NpcMemoryChanged Встретился = ${e.memory.hasMet}, Сколько раз поговорил = ${e.memory.timesTalked}, отдал траву = ${e.memory.receivedHerb}"
        is InteractedWithChest -> "InteractedWithChest ${e.chestId}"
        is GoldCountChanged -> "GoldCountChanged ${e.newCount}"
        is ServerMessage -> "Server: ${e.text}"
        is Win -> "Win"
    }
}

fun main() = KoolApplication {
    val hud = HudState()
    val server = GameServer()

    addScene {
        defaultOrbitCamera()

        // Строим пол из мелких кубиков
        for (x in -5..5) {
            for (z in -4..4) {
                addColorMesh {
                    generate { cube { colored() } }

                    shader = KslPbrShader {
                        color { vertexColor() }
                        metallic(0f)
                        roughness(0.25f)
                    }
                }
                    .transform.translate(x.toFloat(), -1.2f, z.toFloat())
                // Сдвигаем плитку (кубы - пол) в мире
                // y = -1.2f опускаем пол ниже игрока
            }
        }

        val wallCells = listOf(
            GridPos(-1, 1),
            GridPos(0, 1),
            GridPos(1, 1),
            GridPos(1, 0)
        )

        for (cell in wallCells) {
            addColorMesh {
                generate { cube { colored() } }

                shader = KslPbrShader {
                    color { vertexColor() }
                    metallic(0f)
                    roughness(0.25f)
                }
            }
                .transform.translate(cell.x.toFloat(), -1.2f, cell.z.toFloat())
        }

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

        var renderX = 0f
        var renderZ = 0f
        var renderRotate = 0f

        var lastAppliedX = 0f
        var lastAppliedZ = 0f
        var lastAppliedRotate = 0f

        var lastAppliedYaw = 0f
        // yaw - какой поворот уже был применен к PlayerNode

        playerNode.onUpdate {
            val activeId = hud.activePlayerIdFlow.value
            val player = server.getPlayerData(activeId)

            val targetX = player.gridX.toFloat()
            val targetZ = player.gridZ.toFloat()
            val targetRotate = player.rotate

            // Плавность перемещения
            // чем больше коэффицент, тем быстрее куб переходит на новую клетку
            val speed = Time.deltaT * 8f
            val t = if (speed > 1f) 1f else speed

            renderX = lerp(renderX, targetX, t)
            renderZ = lerp(renderZ, targetZ, t)
            renderRotate = lerp(renderRotate, targetRotate, t)

            val dx = renderX - lastAppliedX
            val dz = renderZ - lastAppliedZ
            val rotate = renderRotate - lastAppliedRotate

            playerNode.transform.translate(dx, 0f, dz)
            playerNode.transform.rotate(rotate.deg, Vec3f.Y_AXIS)

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
            .map { event ->
                eventToText(event)
            }
            .onEach { line ->
                hudLog(hud, "[${hud.activePLayerIdUi.value}] $line")
            }
            .launchIn(coroutineScope)

        hud.activePlayerIdFlow
            .flatMapLatest { pid ->
                server.events.filter { it.playerId == pid }
            }
            .filter { it is Win }
            .onEach {
                hud.isWinVisible.value = true
                playWinSoundIfExists(hud)
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

                Text("Игрок: ${hud.activePLayerIdUi.use()}") { modifier.margin(bottom = sizes.gap) }
                Text("Позиция: x=${"%.1f".format(player.gridX)} z=${"%.1f".format(player.gridZ)}") {}
                Text("Смотрит: ${player.facing}") { modifier.font(sizes.smallText).margin(bottom = sizes.smallGap) }
                Text("Quest State: ${player.questState}") { modifier.font(sizes.smallText) }
                Text(currentObjective(player)) { modifier.font(sizes.smallText) }
                Text(formatInventory(player)) { modifier.font(sizes.smallText).margin(bottom = sizes.smallGap) }
                Text("Gold: ${player.gold}") { modifier.font(sizes.smallText) }
                Text("Hint: ${player.hintText}") { modifier.font(sizes.smallText) }
                Text("Npc Memory: ${formatMemory(player.alchemistMemory)}") {
                    modifier.font(sizes.smallText).margin(bottom = sizes.smallGap)
                }

                Row {
                    Button("Сменить игрока") {
                        modifier.margin(end = 8.dp).onClick {
                            val newId = if (hud.activePLayerIdUi.value == "Oleg") "Stas" else "Oleg"

                            hud.activePLayerIdUi.value = newId
                            hud.activePlayerIdFlow.value = newId
                        }
                    }
                    Button("Сбросить игрока") {
                        modifier.onClick {
                            server.trySend(CmdResetPlayer(player.playerId))
                        }
                    }
                }

                Text("Движение в мире:") { modifier.margin(top = sizes.gap) }

                Row {
                    Button("Лево") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdStepMove(player.playerId, stepX = -1, stepZ = 0))
                        }
                    }
                    Button("Право") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdStepMove(player.playerId, stepX = 1, stepZ = 0))
                        }
                    }
                    Button("Вперед") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdStepMove(player.playerId, stepX = 0, stepZ = -1))
                        }
                    }
                    Button("Назад") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdStepMove(player.playerId, stepX = 0, stepZ = -1))
                        }
                    }
                }
                Row {
                    Button("вращать в лево") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdRotate(player.playerId, rotate = -10f))
                        }
                    }
                    Button("вращать в право") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdRotate(player.playerId, rotate = 10f))
                        }
                    }
                    Button("Добавить кубик") {
                        modifier.margin(end = 8.dp).onClick {
                            val cubeNode = addColorMesh {
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
                            var pos: Float = 0f
                            pos += 1.7f

                            cubeNode.transform.translate(3f, pos, 0f)

                        }
                    }
                    Text("Взаимодействия:") { modifier.margin(top = sizes.gap) }

                    Row {
                        Button("Потрогать ближайшего") {
                            modifier.margin(end = 8.dp).onClick {
                                server.trySend(CmdInteract(player.playerId))
                            }
                        }
                    }

                    Text(dialogue.npcId) { modifier.margin(top = sizes.gap) }
                    Text(dialogue.text) { modifier.margin(bottom = sizes.smallGap) }

                    if (dialogue.option.isEmpty()) {
                        Text("Нет доступных варинатов ответа") {
                            modifier.font(sizes.smallText).margin(bottom = sizes.gap)
                        }
                    } else {
                        Row {
                            for (option in dialogue.option) {
                                Button(option.text) {
                                    modifier.margin(end = 8.dp).onClick {
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
                    Text("Лог: ") { modifier.margin(top = sizes.gap) }

                    for (line in hud.log.use()) {
                        Text(line) { modifier.font(sizes.smallText) }
                    }
                }
            }
        }
        addPanelSurface {
            modifier
                .align(AlignmentX.Center, AlignmentY.Center)

            if (hud.isWinVisible.use()) {
                Column {
                    Text("победа") {
                        modifier
                            .background(RoundRectBackground(Color(0f, 0f, 0f, 0.75f), 14.dp))
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    }

                    var provider = hud.winGifProvider.use()
                    if (provider == null) {
                        provider = AnimatedGifImageProvider.fromPath("kotlin/playerGridMovement/confetti.gif")
                        if (provider != null) {
                            hud.winGifProvider.value = provider
                        }
                    }
                    if (provider != null) {
                        Image {
                            modifier
                                .margin(top = 8.dp)
                                .width(360.dp)
                                .height(220.dp)
                                .imageProvider(provider)
                                .imageSize(ImageSize.FitContent)
                                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.4f), 8.dp))
                                .padding(6.dp)
                        }
                    } else {
                        Text("GIF не найдена: kotlin/playerGridMovement/confetti.gif") {
                            modifier.margin(top = 8.dp).background(
                                RoundRectBackground(Color(0f, 0f, 0f, 0.55f), 8.dp)
                            ).padding(horizontal = 10.dp, vertical = 6.dp)
                        }
                    }

                    val soundExists = 
                            File("kotlin/playerGridMovement/victory.mp3").exists()
                    Text(
                        if (soundExists) "Звук победы найден"
                        else "Добавь звук: win.wav / win.ogg / victory.mp3"
                    ) {
                        modifier.margin(top = 6.dp).font(sizes.smallText)
                    }
                }
            }
        }
    }
}

// 1.1 c)
// 1.2 d)
// 1.3 d)