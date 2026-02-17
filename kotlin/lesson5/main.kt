package lesson5

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

enum class ItemType{
    POTION,
    QUEST_ITEM,
    MONEY
}

data class Item(
    val id: String,
    val name: String,
    val type: ItemType,
    val maxStack: Int
)

data class ItemStack(
    val item: Item,
    val count: Int
)

val HERB = Item(
    "herb",
    "Herb",
    ItemType.QUEST_ITEM,
    16
)

val HEALING_POTION = Item(
    "potion_heal",
    "Heal potion",
    ItemType.POTION,
    6
)

class GameState{
    val playerId = mutableStateOf("Oleg")

    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)

    val inventory = mutableStateOf(List<ItemStack?>(5) {null})
    // 5 слотов инвенторя - по умолчанию заполнил

    val selectedSlot = mutableStateOf(0)
    val log = mutableStateOf<List<String>>(emptyList())
}

fun pushLog(game: GameState, text: String) {
    // (старый список + новая строка лога) = новый список
    game.log.value = (game.log.value + text).takeLast(20)
    // takeLast - обрезает спискоБ остовляет только последние 20 строк
}

//------------------------------ Система событий -----------------------//

sealed interface GameEvent{
    val playerId: String
}

data class TalkedToNpc(
    override val playerId: String,
    val npcId: String
) : GameEvent

data class ChoiceSelected(
    override val playerId: String,
    val npcId: String,
    val choiceId: String
) : GameEvent

data class ItemCollected(
    override val playerId: String,
    val itemId: String,
    val count: Int
) : GameEvent

data class ItemGivenToNpc(
    override val playerId: String,
    val npcId: String,
    val itemId: String,
    val count: Int
) : GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val questId: String,
    val newState: String
) : GameEvent

data class PlayerProgressSaved(
    override val playerId: String,
    val questId: String,
    val stateName: String
) : GameEvent

//--------------------------------- Система рассылки событий и подписки на них ----------------- //

typealias Listener = (GameEvent) -> Unit

class EventBus{
    private val listeners = mutableListOf<Listener>()

    fun subscribe(listener: Listener){
        listeners.add(listener)
    }

    fun publish(event: GameEvent){
        for (l in listeners){
            l(event)
        }
    }
}

//--------------------------------- Графы состоянией для не линейного квеста ----------------- //

enum class QuestState{
    START,
    OFFERED,
    ACCEPTED_HELP,
    ACCEPTED_THREAT,
    HERB_COLLECTED,
    GOOF_END,
    EVIL_END
}

class StateGraph<S: Any, E: Any>(
    private val initial: S
    // S и E - обобщенный типы данных (generics)
    // S = State = тип состояния
    // Пример - тут в виде типов будут START, OFFERED....
    // E = Event = тип события
    // Пример - в виде типов данных TalkedToNpc ...
    // Это нужно, чтобы не создовать для каждой системы (квестов, ui и тд) отдельные StateGraph(ы)
    // Данный графБ можно использовать не только для квестов, но и для Ai мобов, UI, диалогов и тд

    // Что значит S: Any - означает, что S не может быть nullable (S не может быть QuestState?)
    // Any - в котлине это "любой не null обькт"
    // private val initial: S - нужно для инициализации начального состояния Графа (то есть точка входа, откуда будет начинать граф QuestState.START)
){
    // Карта переходов transitions - из состояние S -> (тип события -> функция, которая вычилсяет новое состояние)
    private val transitions = mutableMapOf<S, MutableMap<Class<out E>, (E) -> S>>()
    // MutableMap<Class<out E>, (E) -> S>
    // Ключ в виде Class<out E> = класс события (например TalkedToNpc::class.java)
    // (E) -> S "Функция берет событие -> и возрощает новое состояние"

    // on - добовления перехода между состояниями
    fun on(from: S, eventClass: Class<out E>, to: (E) -> S){
        // from: S - из какого состояния
        // eventClass: Class<out E> - при каком типе события
        // to: (E) -> S - условие, как мы получим новое состояние (обычно просто вернуть конкретное значение)
        val byEvent = transitions.getOrPut(from) { mutableMapOf() }
        byEvent[eventClass] = to
    }

    fun next(current: S, event: E): S{
        // Берем карту переходов для данного состояния
        val byEvent = transitions[current] ?: return current

        // Берем класс события
        val eventClass = event::class.java

        // собираем обработчик для данного типа событий
        val handler = byEvent[eventClass] ?: return current

        return handler(event)
    }

    fun initialState(): S = initial
}

class QuestSystem(
    private val bus: EventBus
){
    val questId = "q_alchemist"
    val stateByPlayer = mutableStateOf<Map<String, QuestState>>(emptyMap())

    private val graph = StateGraph<QuestState, GameEvent>(QuestState.START)

    init {
        graph.on(QuestState.START, TalkedToNpc::class.java){ _ ->
            QuestState.OFFERED
        }

        graph.on(QuestState.OFFERED, ChoiceSelected::class.java){ e ->
            val ev = e as ChoiceSelected
            if(ev.choiceId == "help") QuestState.ACCEPTED_HELP else QuestState.ACCEPTED_THREAT

        }
    }
}
