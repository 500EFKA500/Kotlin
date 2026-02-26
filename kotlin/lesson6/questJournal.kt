package lesson6

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
import java.io.File

// startWith('quest:') - проверка с чего начинается строка
// substringAfter('quest:') - добавить "кусок" строки после префикса
// try {что пытаемся сделать} cath (e: Exception) {сделать то, что произойдет в случае "падения" при загрузке try}
// try catch - не "положит" весь код fun main, если произойдет ошибка

class GameState{
    val playerId = mutableStateOf("Oleg")

    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)

    val log = mutableStateOf<List<String>>(emptyList())
}

fun pushLog(game: GameState, text: String){
    game.log.value = (game.log.value + text).takeLast(20)
}

// sealed - иерархия классов
// Это вид класса, который только хранит в себе другие классы
// interface - тип класса, который обязует все дочерныие классы - перезаписать свойства, которые мы положим в втрочиный конструктор
sealed interface GameEvent{
    val playerId: String
}

data class TalkedToNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class ChoiceSelected(
    override val playerId: String,
    val npcId: String,
    val choiceID: String
): GameEvent

data class ItemCollected(
    override val playerId: String,
    val itemId: String,
    val count: Int
): GameEvent

data class ItemGivenToNpc(
    override val playerId: String,
    val itemId: String,
    val count: Int,
    val npcId: String
): GameEvent

data class GoldPaidToNpc(
    override val playerId: String,
    val count: Int,
    val npcId: String
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val questId: String,
    val newStateName: String
): GameEvent

data class PLayerProgressSaved(
    override val playerId: String,
    val reason: String
) : GameEvent

//-------------------------------------

typealias Listener = (GameEvent) -> Unit

class EventBus{
    private val listeners = mutableListOf<Listener>()

    fun subscribe(listener: Listener){
        listeners.add(listener)
    }

    fun publish(event: GameEvent){
        for (listener in listeners){
            listener(event)
        }
    }
}

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val npcName: String,
    val text: String,
    val options: List<DialogueOption>
)

// QuestDefinition - "описание квеста" будет интерфейсомБ то есть набором для вссех квестов, при их создании
// Любой новый квест, при создании будет наследоваться из данного интерфейса все свойства, методы

interface QuestDefinition{
    val questId: String

    fun initialStateName(): String
    // Состояние, которое будет принимать квест, в момент создания

    fun nextStateName(currentStateName: String, event: GameEvent, game: GameState): String
    // Метод, который проверяет нынешнее состояние и возращает следущее, к которому он перейдет при event событии

    fun stateDescription(stateName: String): String
    // Описание этапа квеста, для квестового журнала

    fun npcDialogue(stateName: String): DialogueView
    // Метод указывает, что скажет npc и какие кнопки покажет в диалоге
}

//--------------------- Создание квеста с алхимиком (Экземпляр интерфейса QuestDefinition) ----------

enum class AlchemistState{
    START,
    OFFERED,
    HELP_ACCEPTED,
    HERB_COLLECTED,
    THREAT_ACCEPTED,
    GOOD_END,
    EVIL_END
}

class AlchemistQuest: QuestDefinition{
    override val questId: String = "q_alchemist"

    override fun initialStateName(): String {
        return AlchemistState.START.name
    }

    private fun safeState(stateName: String) : AlchemistState{
        // valueOf - может "положить" код, если строка окажется неправильной
        return try {
            AlchemistState.valueOf(stateName)
        } catch (e: Exception){
            AlchemistState.START
        }
    }

    override fun nextStateName(currentStateName: String, event: GameEvent, game: GameState): String {
        val current = safeState(currentStateName)

        val next: AlchemistState = when(current){
            AlchemistState.START -> when(event){
                is TalkedToNpc -> {
                    if (event.npcId == "Alchemist") AlchemistState.OFFERED else AlchemistState.START
                }
                else -> AlchemistState.START
            }

            AlchemistState.OFFERED -> when(event){
                is ChoiceSelected -> {
                    if (event.npcId != "Aclhemist") AlchemistState.OFFERED
                    else if (event.choiceID == "help") AlchemistState.HELP_ACCEPTED
                    else if (event.choiceID == "threat") AlchemistState.THREAT_ACCEPTED
                    else AlchemistState.OFFERED
                }
                else -> AlchemistState.OFFERED
            }

            AlchemistState.HELP_ACCEPTED -> when(event){
                is ItemCollected -> {
                    if (event.itemId == "herb") AlchemistState.HERB_COLLECTED else AlchemistState.HELP_ACCEPTED
                }
                else -> AlchemistState.OFFERED
            }

            AlchemistState.HERB_COLLECTED -> when(event){
                is ItemGivenToNpc -> {
                    if (event.npcId == "Alchemist" && event.itemId == "herb") AlchemistState.GOOD_END
                    else AlchemistState.HERB_COLLECTED
                }
                else -> AlchemistState.HERB_COLLECTED
            }

            AlchemistState.THREAT_ACCEPTED -> when(event){
                is ChoiceSelected -> {
                    if(event.npcId == "Alchemist" && event.choiceID == "threat_confirm") AlchemistState.EVIL_END
                    else AlchemistState.THREAT_ACCEPTED
                }
                else -> AlchemistState.THREAT_ACCEPTED
            }

            AlchemistState.GOOD_END -> AlchemistState.GOOD_END
            AlchemistState.EVIL_END -> AlchemistState.EVIL_END
        }
        return next.name
    }

    override fun stateDescription(stateName: String): String {
        return when(safeState(stateName)){
            AlchemistState.START -> "Поговорить с Алхимиком"
            AlchemistState.OFFERED -> "Помочь или угрожать"
            AlchemistState.HELP_ACCEPTED -> "Собрать 1 траву"
            AlchemistState.HERB_COLLECTED -> "Отдать траву алхимику"
            AlchemistState.THREAT_ACCEPTED -> "Подтвердить угрозу"
            AlchemistState.GOOD_END -> "Квест звершен (хорошая концовка)"
            AlchemistState.EVIL_END -> "Квест звершен (плохая концовка)"
        }
    }

    override fun npcDialogue(stateName: String): DialogueView {
        return when(safeState(stateName)){
            AlchemistState.START -> DialogueView(
                "Алхимик",
                "Привет! Подойди перетрем за траву",
                listOf(DialogueOption("talk", "Поговорить"))
            )

            AlchemistState.OFFERED -> DialogueView(
                "Алхимик",
                "Мне нужна трава, подсобишь?",
                listOf(
                    DialogueOption("help", "Помочь (принести травы)"),
                    DialogueOption("threat", "Угрожать (требовать золото)")
                )
            )

            AlchemistState.HELP_ACCEPTED -> DialogueView(
                "Алхимик",
                "Принеси 1 траву и мы в расщете",
                listOf(
                    DialogueOption("collect_herb", "Собрать траву"),
                    DialogueOption("talk", "Поговорить еще")
                )
            )

            AlchemistState.HERB_COLLECTED -> DialogueView(
                "Алхимик",
                "Отлично, давай траву",
                listOf(
                    DialogueOption("give_herb", "Отдать траву"),
                )
            )

            AlchemistState.THREAT_ACCEPTED -> DialogueView(
                "Алхимик",
                "Ты уверен, мабой?",
                listOf(
                    DialogueOption("threat_confirm", "Да, гони золото")
                )
            )

            AlchemistState.GOOD_END -> DialogueView(
                "Алхимик",
                "Спасибо, держи зелье здоровья и 50 золота (GOOD END)",
                emptyList()
            )

            AlchemistState.EVIL_END -> DialogueView(
                "Алхимик",
                "Ладно, держи свое золото, но ты об этом пожалеешь (EVIL END)",
                emptyList()
            )
        }
    }
}

// ------ Квест со стражником (вещь или бан) ---------

enum class GuardState{
    START,
    OFFERED,
    WAIT_PAYMENT,
    PASSED,
    BANNED
}

class GuardQuest: QuestDefinition{
    override val questId: String = "q_guard"

    override fun initialStateName(): String = GuardState.START.name

    private fun safeState(stateName: String) : GuardState{
        // valueOf - может "положить" код, если строка окажется неправильной
        return try {
            GuardState.valueOf(stateName)
        } catch (e: Exception){
            GuardState.START
        }
    }

    override fun nextStateName(currentStateName: String, event: GameEvent, game: GameState): String {
        val current = safeState(currentStateName)

        val next: GuardState = when(current){
            GuardState.START -> when(event){
                is TalkedToNpc -> {
                    if(event.npcId == "guard") GuardState.OFFERED else GuardState.START
                }
                else -> GuardState.START
            }

            GuardState.OFFERED -> when (event){
                is ChoiceSelected -> {
                    if (event.npcId != "guard") GuardState.OFFERED
                    else if (event.choiceID == "pay") GuardState.WAIT_PAYMENT
                    else if (event.choiceID == "refuse") GuardState.BANNED
                    else GuardState.OFFERED
                }
                else -> GuardState.OFFERED
            }

            GuardState.WAIT_PAYMENT -> when(event){
                is GoldPaidToNpc -> {
                    if (event.npcId == "guard" && event.count >= 5) GuardState.PASSED
                    else GuardState.WAIT_PAYMENT
                }
                else -> GuardState.WAIT_PAYMENT
            }

            GuardState.PASSED -> GuardState.PASSED
            GuardState.BANNED -> GuardState.BANNED
        }
        return next.name
    }

    override fun stateDescription(stateName: String): String {
        return when(safeState(stateName)){
            GuardState.START -> "Поговорить со Стражем"
            GuardState.OFFERED -> "Выбрать действие"
            GuardState.WAIT_PAYMENT -> "Дать деньги"
            GuardState.BANNED -> "разбан на https://minecraft.encorefamily.ru/"
            GuardState.PASSED -> "разбан на всякий https://minecraft.encorefamily.ru/"
        }
    }

    override fun npcDialogue(stateName: String): DialogueView {
        return when(safeState(stateName)){
            GuardState.START -> DialogueView(
                "Страж",
                "Привет, подойди, я добрый",
                listOf(DialogueOption("talk", "Поговорить"))
            )

            GuardState.OFFERED -> DialogueView(
                "Страж",
                "Я НА САМОМ ДЕЛЕ ПЛОХОЙ, ПЛАТИ ИЛИ ТЫ ЗАБАНЕН",
                listOf(
                    DialogueOption("pay", "Заплатить"),
                    DialogueOption("refuse", "Отказаться")
                )
            )

            GuardState.BANNED -> DialogueView(
                "Страж",
                "ТЫ ЗАБАНЕН купить разбан на https://minecraft.encorefamily.ru/",
                emptyList()
            )

            GuardState.WAIT_PAYMENT -> DialogueView(
                "Страж",
                "ПЛАТИ ДЕНЬГИ!!!",
                listOf(DialogueOption("guard", "Хорошо"))
            )

            GuardState.PASSED -> DialogueView(
                "Страж",
                "Молоде, но ты всё ровно должен купить разбан https://minecraft.encorefamily.ru/",
                emptyList()
            )
        }
    }
}

class QuestManager(
    private val bus: EventBus,
    private val game: GameState,
    private val quests: List<QuestDefinition>
){
    val stateByPlayer = mutableStateOf<Map<String, Map<String, String>>>(emptyMap())
    // Внешний ключ playerID
    // Внутренний ключ - questId
    // Внутреннее значение - состояние квеста на момент сохранения

    init {
        bus.subscribe { event ->
            handleEvent(event)
        }
    }

    private fun handleEvent(event: GameEvent){
        val player = event.playerId

        for (quest in quests){
            val current = getStateName(player, quest.questId)
            val nextState = quest.nextStateName(current, event, game)

            if (nextState == current) continue
            setStateName(player, quest.questId, current)

            bus.publish(QuestStateChanged(player, quest.questId, nextState))
            bus.publish(PLayerProgressSaved(player, "автосохранение"))
        }
    }

    fun getStateName(playerId: String, questId: String): String{
        val playerMap = stateByPlayer.value[playerId]

        if (playerMap == null){
            val def = quests.firstOrNull{it.questId == questId}
            return  def?.initialStateName() ?: "UNKNOW"
        }

        return playerMap[questId] ?: (quests.firstOrNull{it.questId == questId}?.initialStateName() ?: "UNKNOW")
    }

    fun setStateName(playerId: String, questId: String, stateName: String){
        val outerCopy = stateByPlayer.value.toMutableMap()

        val innerOld = outerCopy[playerId] ?: emptyMap()

        val innerCopy = innerOld.toMutableMap()
        innerCopy[playerId] = stateName

        outerCopy[playerId] = innerCopy.toMap()
        stateByPlayer.value = outerCopy.toMap()
    }
}

class SaveSystem(
    private val bus: EventBus,
    private val game: GameState,
    private val questManager: QuestManager,
    private val quests: List<QuestDefinition>,
) {
    init {
        bus.subscribe { event ->
            if (event is PLayerProgressSaved) {
                saveAllForPlayer(event.playerId)
            }
        }
    }

    private fun saveFile(playerId: String): File{
        val dir = File("saves")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${playerId}.save")
    }

    private fun saveAllForPlayer(playerId: String) {
        val f = saveFile(playerId)

        val sb = StringBuilder()
        // Легкое создание и изменение изменяемых последовательных символов
        // В отличии от String - StringBuilder дает возможномть, добовлять, вставлять и изменять сиволы -
        // Без создание обьктов

        sb.append("playerId=").append(playerId).append("\n")
        sb.append("hp=").append(game.hp.value).append("\n")
        sb.append("gold=").append(game.gold.value).append("\n")

        for (q in quests){
            val stateName = questManager.getStateName(playerId, q.questId)
            sb.append("quest: ").append(q.questId).append("=").append(stateName).append("\n")
            // quests: - префикс для отличия строк с квестами от свойств
        }
        f.writeText(sb.toString())
    }
    // Почему используем StringGuilder
    // Когда мы использует обычные строки val text =

    // каждый + создает новую строку (обькт)
    // И если строк много (а их будет много, из-за больших сохранений, файлов логов, файлов настроект)
    // То генерируется лишнее врменнные строки, появляется лишняя нагрузка на память, тяжелая читаемость когда строка

    // Что в данной стории делает StringBuilder (он как коробка, в которую постепенно дописывается текст)
    // append - добовляет не новую строку(как в списке), а новый кусок текста внутрь общей коробки строк
    // В конце "билда" делаем toString - преобразет в итоговую ОДНУ финальную строрку

    fun loadAllForPlayer(playerId: String){
        val f = saveFile(playerId)
        if (!f.exists()) return
        // Прервать, если файла сохранения нет

        val map = mutableMapOf<String, String>()

        for (line in f.readLines()){
            val part = line.split("=")
            if(part.size == 2){
                map[part[0]] = part[1]
            }
        }
        val loadedHp = map["hp"]?.toIntOrNull() ?: 100
        val loadedGold = map["gold"]?.toIntOrNull() ?: 0

        game.hp.value = loadedHp
        game.gold.value = loadedGold

        // Загрузка квестов
        for ((key, value ) in map){
            if (key.startsWith("quest:")){
                // StartWith - проверка, на то, с чего начинается кусок строки

                val questId = key.substringAfter("quest:")
                // substringAfter - берет чать строки, после quest:
                // Пример ключ "quest:q_guard" -> substringAfter вернут только q_guard

                questManager.setStateName(playerId, questId, value)
                // Подргужаем этап квеста, на котором остоновился игрок во время сохранения
            }
        }
    }
}

fun main() = KoolApplication {
    val game = GameState()
    val bus = EventBus()

    val alchemistQuest = AlchemistQuest()
    val guardQuest = GuardQuest()

    val questList = listOf<QuestDefinition>(alchemistQuest, guardQuest)
    // Создаем список квестов и кладем туда наши квесты

    val questManager = QuestManager(bus, game, questList)
    val saves = SaveSystem(bus, game, questManager, questList)

    val activeNpcId = mutableStateOf<String?>(null)
    // если у npc null значит игрок ещё не открыл диалог с ним
}