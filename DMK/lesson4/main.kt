package lesson4

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

import lesson3.ItemType
import lesson3.Item
import lesson3.ItemStack
import lesson3.HEALING_POTION
import lesson3.SWORD
import lesson3.GameState
import lesson3.ItemAdded
import lesson3.putIntoSlot
import lesson3.useSelected
import java.io.File

sealed interface GameEvent{
    val playerId: String
}

data class QuestStepCompleted(
    override val playerId: String,
    val questId: String,
    val stepId: Int
): GameEvent

data class PlayerProgressSaved(
    override val playerId: String,
    val questId: String,
    val stepId: Int
): GameEvent

data class DamageDealt(
    override val playerId: String,
    val targetId: String,
    val amount: Int,
): GameEvent

data class ItemUsed(
    override val playerId: String,
    val itemId: String
) : GameEvent

data class EffectApplied(
    override val playerId: String,
    val effectId: String,
    val ticks: Int
) : GameEvent


typealias Listener = (GameEvent) -> Unit
class EventBus{
    // typeAlias - переменная хранящая в себе тип данных
    private val listeners = mutableListOf<Listener>()

    fun subscribe(listener: Listener){
        listeners.add(listener)
    }

    fun publish(event: GameEvent){
        for(listener in listeners){
            listener(event)
        }
    }
}

class QuestSystem(
    private val bus: EventBus
){
    val questId = "q_training"
    val progressByPlayer = mutableStateOf<Map<String, Int>>(emptyMap())

    fun getStep(playerId: String): Int{
        return progressByPlayer.value[playerId] ?: 0
    }

    fun setStep(playerId: String, step:Int){
        val copy = progressByPlayer.value.toMutableMap()
        copy[playerId] = step
        progressByPlayer.value = copy.toMap()
    }

    fun completeStep(playerId: String, stepId: Int){
        val next = stepId + 1

        setStep(playerId, next)

        bus.publish(
            QuestStepCompleted(
                playerId,
                questId,
                stepId
            )
        )

        // Публикуем сразу! событие сохранения прогресса игрока
        // То есть этапы квеста - будут как контрольные точки в игре
        bus.publish(
            PlayerProgressSaved(
                playerId,
                questId,
                next
            )
        )
    }
}

class SaveSystem(
    private val bus: EventBus,
    private val game: GameState,
    private val quest: QuestSystem
){
    init{
        bus.subscribe { event ->
            // Ожидаем событие сохранения прогресса, и когда оно прилетит - пишем в файл
            if (event is PlayerProgressSaved){
                saveProgress(event.playerId, event.questId, event.stepId)
            }
        }
    }
    private fun saveFile(playerId: String, questId: String): File{
        val dir = File("saves")
        if(!dir.exists()){
            dir.mkdirs() // mkdirs - создает папку (и родителей этой папки), если ее нет
        }

        // Имя файла: saves/player_1_q_training.save
        return File(dir, "${playerId}_${questId}.save")
    }

    fun saveProgress(playerId: String, questId: String, stepId: Int){
        val f = saveFile(playerId, questId)

        // Простое хранение сохранения в формате ключ = значение
        val text =
            "playerId=${playerId}\n" +
                    "questId=${questId}\n" +
                    "stepId=${stepId}\n" +
                    "hp=${game.hp.value}\n" +
                    "gold=${game.gold.value}\n"

        f.writeText(text) // writeText - записать в файл строку
    }

    fun loadProgress(playerId: String, questId: String){
        val f = saveFile(playerId, questId)
        if (!f.exists()) return

        val lines = f.readLines() // Чтение файла построчно

        val map = mutableMapOf<String, String>()

        for(line in lines){
            val parts = line.split("=") // split делит строку на части (=) здесь разделитель
            if (parts.size == 2){
                val key = parts[0]
                val value = parts[1]
                map[key] = value
            }
        }

        val loadedStep = map["stepId"]?.toIntOrNull() ?: 0
        // ?. - "Если не null - то вызови toIntOrNull"
        // toIntOrNull - пытается превратить строку в Int, иначе null
        // ?: - если получили null -> вернуть 0
        val loadedHp = map["hp"]?.toIntOrNull() ?: 100
        val loadedGold = map["gold"]?.toIntOrNull() ?: 0

        game.hp.value = loadedHp
        game.gold.value = loadedGold

        quest.setStep(playerId, loadedStep)
    }
}

fun pushLog(game: GameState, text: String){
    game.eventLog.value = (game.eventLog.value + text).takeLast(20)
}


fun main() = KoolApplication{
    val game = GameState()
    val bus = EventBus()
    val quests = QuestSystem(bus)
    val saves = SaveSystem(bus, game, quests)

    bus.subscribe { event ->
        val line = when (event) {
            is ItemAdded -> "ItedAdded: ${event.itemId} + ${event.countAdded} (осталось: ${event.leftOver})"
            is ItemUsed -> "ItemUsed: ${event.itemId}"
            is PlayerProgressSaved -> "Game Saved: ${event.questId} Step: ${event.stepId}"
            is DamageDealt -> "DamageDealt: ${event.amount} - ${event.targetId}"
            is EffectApplied -> "EffectApplied: ${event.effectId} +${event.ticks}"
            is QuestStepCompleted -> "QuestStepCompleted: ${event.questId} шаг: ${event.stepId + 1}"
            else -> {}
        }
        pushLog(game, "[${event.playerId}] $line")
    }
    addScene {
        defaultOrbitCamera()

        addColorMesh {
            generate { cube { colored() } }

            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0.8f)
                roughness(0.5f)
            }

            onUpdate { transform.rotate(45f.deg * Time.deltaT, Vec3f.X_AXIS) }
        }

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }

        var potionTimeSec = 0f
        var regenTimeSec = 0f
        onUpdate {
            if (game.potionTicksLeft.value > 0) {
                potionTimeSec += Time.deltaT
                if (potionTimeSec >= 1f) {
                    potionTimeSec = 0f
                    game.potionTicksLeft.value -= 1
                    game.hp.value = (game.hp.value - 2).coerceAtLeast(0)
                }
            } else {
                potionTimeSec = 0f
            }

            if (game.regenTicksLeft.value > 0) {
                regenTimeSec += Time.deltaT
                if (regenTimeSec >= 1f) {
                    regenTimeSec = 0f
                    game.regenTicksLeft.value -= 1
                    game.hp.value = (game.hp.value + 1).coerceAtLeast(0)
                }
            } else {
                regenTimeSec = 0f
            }
        }
    }

    addScene {
        setupUiScene(ClearColorLoad)

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))
                .padding(12.dp)

            Column {
                Text("Игрок: ${game.playerId.use()}"){}
                Text("HP: ${game.hp.use()}"){
                    modifier.margin(bottom = sizes.gap)
                }

                val step = quests.progressByPlayer.use()[game.playerId.use()] ?: 0
                Text("Прогресс квеста: $step"){
                    modifier.margin(bottom = sizes.gap)
                }

                Text("Выбранный слот: ${game.selectedSlot.use() + 1}"){
                    modifier.margin(bottom = sizes.gap)
                }
                Row{
                    Button("Сменить игрока"){
                        modifier.margin(end=8.dp).onClick {
                            game.playerId.value =
                                if (game.playerId.value == "Player") "Oleg" else "Player"
                        }
                    }

                    Button("Загрузить последнее сохранение"){
                        modifier.onClick{
                            saves.loadProgress(game.playerId.value, quests.questId)
                            pushLog(game, "[${game.playerId.value}] Загрузил сохранение из квеста ${quests.questId}")
                        }
                    }
                }
                Row { modifier.margin(top = sizes.smallGap)
                    Button("Получить меч(Шаг 0)"){
                        modifier.margin(end=8.dp).onClick{
                            val pid = game.playerId.value
                            quests.completeStep(pid, stepId = 0)
                        }
                    }
                    Button("Ударить манекен(Шаг 1)"){
                        modifier.onClick{
                            val pid = game.playerId.value
                            quests.completeStep(pid, stepId = 1)
                        }
                    }
                }
                Text("Лог событий") {
                    modifier.margin(top = sizes.gap)
                }

                val lines = game.eventLog.use()

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

// 1. Сделать нанесение урона случайным значением
// - Например рука (1-5крит) меч(7-18крит)
// - В консоли выводить нанесенный случайный урон

// 2. “квест выполняется отдельно для каждого игрока”
// - Нажми Switch Player btn -> стань player_2
// Нажми Get Sword btn (step0) -> прогресс у player_2 станет 1
// Вернись на player_1 -> у него прогресс должен быть свой (не смешанный)

// 3. Кнопка с выбором сохранения
// - Выводить список в виде кнопок, всех сохранений, что хранятся в файле saves конкретного игрока
// - При нажатии по любой кнопке сохранения - это сохранение должно загрузиться

// Контрольную сдать полный репозиторием с тем, что вы писали на уроках

// import java.io.File






