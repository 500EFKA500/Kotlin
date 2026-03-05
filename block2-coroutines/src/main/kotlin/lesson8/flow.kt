package lesson8

// старая система событий EventBus на списках Listeners - нормально для стартовых примеров, но в будущем вызовет проблемы
// например чем больше наша программа, тем сложнее работать с подписками, отменами, конкуренций кто и когда слушает
// Для аналога есть Flow - это готовая стандартная система событий из kotlin coroutines

// Прежде система сохранений "key=value" вручную - быстро и удобно для дема, но в реальной игре превращается в ад:
// Много полей -> много ошибок -> трудные миграции
// kotlinx.serialization позволяет сохранять объекты почти одной строкой в формат JSON (и обратно)
// Json.encodeToString() / decodeFromString()

// -------------Flow------------- //
// Flow
// Пример:
// Есть радиостанция - она пускает события, а слушатели подписываются и получают информацию о событиях
// Во Flow есть два главных варианта
// 1. SharedFlow - наше радио событий
// Это как поток радиостанции, трансляции и тд - он существует, даже когда никто не слушает и раздает события всем подписчикам
// Аналогия с GameEvent (ударил, квест обновился, нпс сказал ....)
// 2. StateFlow - табло состояний
// Это тоже поток, который хранит одно текущее состояние и раздает всем подписчикам последнее известное состояние
// Идеально для ServerState, PlayerState, QuestJournal ....

// ---- сохранения через сериализацию ---- //
// будем сохранять не строки вручную, а объект целиком
// PLayerData(hp, gold, ...) - это надежнее и легко расширяемо (добавил поле оно сразу попало в JSON)
// @Serializable - аннотацией (пометкой) "этот класс, который мы пометили можно сохранить или загрузить"

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
import kotlinx.coroutines.flow.MutableSharedFlow    // радиостанция событий
import kotlinx.coroutines.flow.SharedFlow           // только чтение для подписчиков
import kotlinx.coroutines.flow.MutableStateFlow     // табло состояний
import kotlinx.coroutines.flow.StateFlow            //только для чтения состояний
import kotlinx.coroutines.flow.asSharedFlow         // отдать наружу только SharedFlow
import kotlinx.coroutines.flow.asStateFlow          // отдать только StateFlow
import kotlinx.coroutines.flow.collect              // слушать поток

// Импорты Serialization
import kotlinx.serialization.Serializable           // аннотация, что можно сохранять
import kotlinx.serialization.encodeToString         // Запись в файл
import kotlinx.serialization.decodeFromString       // Чтение с файла
import kotlinx.serialization.json.Json              // Формат файла Json
import lesson7.CmdLoadPlayer
import lesson7.CmdSavePlayer
import lesson7.QuestState
import lesson7.pushLog

import java.io.File                                 // для работы с файлами
import kotlin.math.log

// События игры создаем как раньше, но отпровлять будем через Flow

sealed interface GameEvent{
    val playerId: String
}

data class DamageDealt(
    override val playerId: String,
    val targetId: String,
    val amount: Int
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val questId: String,
    val newState: String
): GameEvent

data class ApplyLoader(
    override val playerId: String,
    val playerSave: PlayerSave
): GameEvent

data class PlayerProgressSaved(
    override val playerId: String,
    val reason: String
): GameEvent

// Серверные данные игрока - то, что мы хотим сохранить

@Serializable
data class PlayerSave(
    val playerId: String,
    val hp: Int,
    val gold: Int,
    val questStates: Map<String, String>    // Карта состояний questId -> newState
)

class ServerWorld(
    initialPlayerId: String
){
    // MutableSharedFlow - мы будем класть и приостанавливать выполнения события, пока подписчик ек освободится
    // replay = 0 - это означает, "не пересылать старые события, новым подписчикам"
    private val _event = MutableSharedFlow<GameEvent>(replay = 0)

    val events: SharedFlow<GameEvent> = _event.asSharedFlow()
    // сохраняем только в режиме для чтения (изменить нельзя)

    private val _playerState = MutableStateFlow(
        PlayerSave(
            initialPlayerId,
            100,
            0,
            mapOf("q_training" to "START")
        )
    )

    val playerState: StateFlow<PlayerSave> = _playerState.asStateFlow()

    // Команды сервера
    fun dealDamage(playerId: String, targetId: String, amount: Int){
        val old = _playerState.value

        val newHp: Int = (old.hp - amount).coerceAtLeast(0)

        _playerState.value = old.copy(hp = newHp)
    }

    fun setQuestState(playerId: String, questId: String, newState: String){
        val old = _playerState.value

        val newQuestState = old.questStates + (questId to newState)

        _playerState.value = old.copy(questStates = newQuestState)
    }

    suspend fun emitEvent(event: GameEvent){
        _event.emit(event)
        // emit - будет рассылать событием всем подписчикам
        // emit может разослать событие не сразу, если подписчики медленные (очередь потоков)
        // Готовим события заранее и рассылаем его уже в корутине
    }

    suspend fun applyLoaded(playerSave: PlayerSave? = null){
        if (playerSave != null){
            _playerState.value = playerSave
            emitEvent(PlayerProgressSaved(playerSave.playerId, "ручное сохранение"))
        }
    }
}

// Сериализация - сохранение данных в файл
class SaveSystem{
    // Настройка формата сериализации
    // prettyprint - просто делает json красивым и читаемым структурно
    // encodedefaults - значение по умолчанию тоже будут записываться в файл
    private val json = Json{
        prettyPrint = true
        encodeDefaults = true
    }

    private fun saveFile(playerId: String): File{
        val dir = File("saves")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$playerId.json")
    }

    fun save(player: PlayerSave){
        val text = json.encodeToString(player)

        saveFile(player.playerId).writeText(text)
    }

    suspend fun load(playerId: String): PlayerSave?{
        val file = saveFile(playerId)
        if(!file.exists()) return null

        val text = file.readText()

//        if (json.decodeFromString<PlayerSave>(text) != null){
//            ServerWorld(playerId).emitEvent(ApplyLoader(playerId, json.decodeFromString<PlayerSave>(text)))
//        }
        return try {
            json.decodeFromString<PlayerSave>(text)
        }catch (e: Exception){
            null
        }
    }
}

class UiState{
    // состояния внутри него будут обновляться от серверных данных

    val activePlayerId = mutableStateOf("Oleg")
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)

    val questState = mutableStateOf("START")

    val log = mutableStateOf<List<String>>(emptyList())
}

fun pushLog(ui: UiState, text: String){
    ui.log.value = (ui.log.value + text).takeLast(20)
}

fun main() = KoolApplication{
    val ui = UiState()

    val server = ServerWorld(initialPlayerId = ui.activePlayerId.value)
    val save = SaveSystem()

    addScene {
        defaultOrbitCamera()

        addColorMesh {
            generate { cube{colored()} }

            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0.7f)
                roughness(0.4f)
            }

            onUpdate{
                transform.rotate(45f.deg * Time.deltaT, Vec3f.X_AXIS)
            }
        }

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }

        // Подписки на Flow надо запускать в корутинах
        // в Kool у сцены есть coroutineScope тут и запускаем

        // Подписка 1: слушаем события server.events
        coroutineScope.launch {
            server.events.collect { event ->
                // collesst - слушает поток (каждое событие будет попадать в данный слушатель)
                val line = when(event) {
                    is DamageDealt -> pushLog(ui, "${event.playerId} нанем ${event.amount} урона ${event.targetId}")
                    is QuestStateChanged -> pushLog(ui, "${event.playerId} прешел на этап: ${event.newState} квеста ${event.questId}")
                    is PlayerProgressSaved -> pushLog(ui, "Сохранен прогресс ${event.playerId} по причине ${event.reason}")
                    else -> {}
                }
                pushLog(ui, "[${event.playerId}] ${line}")
            }
        }

        // Подписка 2: Слушатель состояний server.playerState

        server.playerState.collect { state ->

            ui.activePlayerId.value = state.playerId
            ui.hp.value = state.hp
            ui.gold.value = state.gold
        }
    }
    addScene {
        setupUiScene(ClearColorLoad)

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))

            Column {
                Row { modifier.margin(top = sizes.smallGap)
                    Button("Загрузить сохранение"){
                        modifier.margin(end=8.dp).onClick {
                            save.save(server.playerState.value)
                        }
                    }

                    Button("Сохраниться"){
                        modifier.margin(end=8.dp).onClick {
                            suspend {
                                val saves = save.load(ui.activePlayerId.value)
                                server.applyLoaded(saves)
                            }
                        }
                    }
                }
                val lines = ui.log.use()

                Column {
                    modifier.margin(top = sizes.smallGap)

                    for (line in lines) {
                        Text(line) {
                            modifier.font(sizes.smallText)
                        }
                    }
                }
            }
        }
    }
}