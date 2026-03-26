package quest_Dependence



import de.fabmax.kool.KoolApplication               // Запускает Kool-приложение
import de.fabmax.kool.PassData
import de.fabmax.kool.addScene                      // функция - добавить сцену (UI, игровой мир и тд)
import de.fabmax.kool.math.Vec3f                    // 3D - вектор (x,y,z)
import de.fabmax.kool.math.deg                      // deg - превращение числа в градусы
import de.fabmax.kool.scene.*                       // Сцена, камера, источники света и тд
import de.fabmax.kool.modules.ksl.KslPbrShader      // готовый PBR Shader - материал
import de.fabmax.kool.util.Color                    // Цветовая палитра
import de.fabmax.kool.util.Time                     // Время deltaT - сколько прошло секунд между двумя кадрами
import de.fabmax.kool.pipeline.ClearColorLoad       // Режим говорящий не очищать экран от элементов (нужен для UI)
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.modules.ui2.UiModifier.*

import kotlinx.coroutines.launch                    // запускает корутину
import kotlinx.coroutines.Job                       // контроллер запущенной корутины
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive                  // проверка жива ли еще корутина - полезный для циклов
import kotlinx.coroutines.delay

import kotlinx.coroutines.flow.MutableSharedFlow    // радиостанция событий
import kotlinx.coroutines.flow.SharedFlow           // только чтение для подписчиков
import kotlinx.coroutines.flow.MutableStateFlow     // табло состояний
import kotlinx.coroutines.flow.StateFlow            //только для чтения состояний
import kotlinx.coroutines.flow.asSharedFlow         // отдать наружу только SharedFlow
import kotlinx.coroutines.flow.asStateFlow          // отдать только StateFlow
import kotlinx.coroutines.flow.collect

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.flatMapLatest

// ---------- Зависимости и блокировка квестов ----------- //

enum class QuestStatus{
    LOCKED,
    ACTIVE,
    COMPLETED
}

enum class QuestMarker{
    NEW,
    PINNED,
    COMPLETED,
    LOCKED,
    NONE
}

enum class QuestBranch{
    NONE,
    HELP,
    THREAT,
    LISTEN,
    DENY
}

data class QuestStateOnServer(
    val questId: String,
    val title: String,
    val status: QuestStatus,
    val step: Int,
    val branch: QuestBranch,
    val progressCurrent: Int,
    val progressTarget: Int,
    val isNew: Boolean,
    val isPinned: Boolean,
    val unlockRequiredQuestId: String?
)

data class QuestJournalEntry(
    val questId: String,
    val title: String,              // Отоброжаемое название квеста
    val status: QuestStatus,
    val objectiveText: String,      // Подсказка "что делать дальше"
    val progressText: String,
    val progressBar: String,
    val marker: QuestMarker,
    val markerHint: String,
    val branchText: String,
    val lockedReason: String
)

// ----------- События, что будут влиять на UI и другие системы ---------- //

sealed interface GameEvent{
    val playerId: String
}

data class QuestBranchChosen(
    override val playerId: String,
    val questId: String,
    val branch: QuestBranch
): GameEvent

data class ItemCollected(
    override val playerId: String,
    val itemID: String,
    val countAdded: Int
): GameEvent

data class GoldTurnedIn(
    override val playerId: String,
    val questId: String,
    val amount: Int
): GameEvent

data class RunJohn(
    override val playerId: String,
    val questId: String,
    val amount: Int
): GameEvent

data class QuestCompleted(
    override val playerId: String,
    val questId: String
): GameEvent

data class QuestUnlocked(
    override val playerId: String,
    val questId: String
): GameEvent

data class QuestJournalUpdated(
    override val playerId: String
) : GameEvent

// Игрок открыл квест - поменять маркер NEW
data class QuestOpened(
    override val playerId: String,
    val questId: String
) : GameEvent

data class QuestPinned(
    override val playerId: String,
    val questId: String
) : GameEvent

data class QuestProgress(
    override val playerId: String,
    val questId: String
) : GameEvent

data class ServerMessage(
    override val playerId: String,
    val text: String
): GameEvent

// ------------ Команды UI -> Сервер ------------ //

sealed interface GameCommand{
    val playerId: String
}

data class CmdChooseBranch(
    override val playerId: String,
    val questId: String,
    val branch: QuestBranch
): GameCommand

data class CmdCollectItem(
    override val playerId: String,
    val itemID: String,
    val countAdded: Int
): GameCommand

data class CmdTurnInGold(
    override val playerId: String,
    val amount: Int,
    val questId: String
): GameCommand

data class CmdGiveGoldDebug(
    override val playerId: String,
    val questId: String,
    val amount: Int
): GameCommand

data class CmdFinishQuest(
    override val playerId: String,
    val questId: String
): GameCommand

// Игрок открыл квест - поменять маркер NEW
data class CmdOpenQuest(
    override val playerId: String,
    val questId: String
) : GameCommand

data class CmdPinQuest(
    override val playerId: String,
    val questId: String
) : GameCommand

data class CmdProgressQuest(
    override val playerId: String,
    val questId: String
) : GameCommand

data class CmdSwitchPlayer(
    override val playerId: String,
    val newPlayerId: String
) : GameCommand

data class PlayerData(
    val playerId: String,
    val gold: Int,
    val inventory: Map<String, Int>
)

class QuestSystem {
    // Здесь прописываем текст целей квеста по шагам для каждого квеста
    fun objectiveFor(q: QuestStateOnServer): String {

        if (q.status == QuestStatus.LOCKED){
            return "Квест пока не доступен"
        }

        if (q.questId == "q_alchemist"){
            return when(q.step){
                0 -> "Поговори с Алхимиком"
                1 -> {
                    when(q.branch){
                        QuestBranch.NONE -> "Выбери путь: Help или Threat"
                        QuestBranch.HELP -> "Собери траву ${q.progressCurrent} / ${q.progressTarget}"
                        QuestBranch.THREAT -> "Собери золото ${q.progressCurrent} / ${q.progressTarget}"
                        else -> ""
                    }
                }
                2 -> "Вернись к Алхимику и заверши квест"
                else -> "Квест завершен"
            }
        }
        if (q.questId == "q_guard"){
            return when(q.step){
                0 -> "Поговори со стражником"
                1 -> "Заплати стражнику золото: ${q.progressCurrent} / ${q.progressTarget}"
                2 -> "Сдай квест стражнику"
                else -> "Квест завершен"
            }
        }
        if (q.questId == "q_bigBabJohn"){
            return when(q.step){
                0 -> "Поговори с Джоном"
                1 -> {
                    when(q.branch){
                        QuestBranch.NONE -> "Выбери путь: LISTEN"
                        QuestBranch.LISTEN -> "Сделай колонку ${q.progressCurrent} / ${q.progressTarget}"
                        QuestBranch.DENY -> "Убеги от плохого джона"
                        else -> ""
                    }
                }
                2 -> "Вернись к джону"
                else -> "квест завершен"
            }
        }
        return "Неизвестный квест"
    }

    // Подсказки куда идти - в будующем использовать для карты и компаса
    fun markerHintFor(q: QuestStateOnServer): String {
        if (q.status == QuestStatus.LOCKED){
            return "Сначала разблокируй квест квест"
        }

        if (q.questId == "q_alchemist"){
            return when (q.step){
                0 -> "NPC: Алхимик"
                1 -> {
                    when(q.branch){
                        QuestBranch.NONE -> "Выбери вариант диалога"
                        QuestBranch.HELP -> "Собери траву"
                        QuestBranch.THREAT -> "Найди золото"
                        else -> ""
                    }
                }
                2 ->"NPC: Алхимик"
                else -> "Готово"
            }
        }

        if (q.questId == "q_guard"){
            return when(q.step){
                0 -> "NPC: Стражник"
                1 -> "Найди зотоло для оплаты"
                2 -> "NPC: Стражниу"
                else -> "Готово"
            }
        }
        if (q.questId == "q_bigBabJohn"){
            return when (q.step){
                0 -> "NPC: Джон"
                1 -> {
                    when(q.branch){
                        QuestBranch.NONE -> "Выбери вариант диалога"
                        QuestBranch.LISTEN -> "Собери колонку"
                        QuestBranch.DENY -> "Убеги от джона"
                        else -> ""
                    }
                }
                2 ->"NPC: Джон"
                else -> "Готово"
            }
        }
        return ""
    }

    fun branchTextFor(branch: QuestBranch): String{
        return when(branch){
            QuestBranch.NONE -> "Путь не выбран"
            QuestBranch.HELP -> "Путь помощи"
            QuestBranch.THREAT -> "Путь угрозы"
            QuestBranch.LISTEN -> "Путь музыки"
            QuestBranch.DENY -> "Путь отказа"
        }
    }

    fun lockedReasonFor(q: QuestStateOnServer): String{
        if (q.status != QuestStatus.LOCKED) return ""


        return if(q.unlockRequiredQuestId == null){
            "причина блокировки неизестна"
        }else{
            "Нужно завершить квест ${q.unlockRequiredQuestId}"
        }
    }

    fun markerFor(q: QuestStateOnServer): QuestMarker{
        return when{
            q.status == QuestStatus.LOCKED -> QuestMarker.LOCKED
            q.status == QuestStatus.COMPLETED -> QuestMarker.COMPLETED
            q.isPinned -> QuestMarker.PINNED
            q.isNew -> QuestMarker.NEW
            else -> QuestMarker.NONE
        }
    }

    fun progressBarText(current: Int, target: Int, blocks: Int = 10): String{
        if(target <= 0) return ""

        val ratio = current.toFloat() / target.toFloat()
        // ratio - отношение прогресса к цели

        val filled = (ratio* blocks).toInt().coerceIn(0, blocks)
        // coerceIn - ограничивает от нуля до ... blocks (10) числа

        fun empty() = blocks - filled

        return "▮".repeat(filled) + "▒".repeat(empty())
    }

    fun progressBarPercent(current: Int, target: Int): String {
        if (target <= 0) return ""

        val percent = (current.toFloat() / target.toFloat() * 100).toInt().coerceIn(0, 100)
        val bar = progressBarText(current, target, 10)

        return "$bar $percent%"
    }

    fun toJournalEmpty(q: QuestStateOnServer): QuestJournalEntry{
        val progressText = if(q.progressTarget > 0) "${q.progressCurrent} / ${q.progressTarget}" else ""

        val progressBar = if(q.progressTarget > 0) progressBarText(q.progressCurrent, q.progressTarget) else ""

        return QuestJournalEntry(
            q.questId,
            q.title,
            q.status,
            objectiveFor(q),
            progressText,
            progressBar,
            markerFor(q),
            markerHintFor(q),
            branchTextFor(q.branch),
            lockedReasonFor(q)
        )
    }
    fun applyEvent(
        quests: List<QuestStateOnServer>,
        event: GameEvent
    ): List<QuestStateOnServer>{
        val copy = quests.toMutableList()

        for (i in copy.indices){
            val q = copy[i]

            if (q.status == QuestStatus.LOCKED) continue
            if (q.status == QuestStatus.COMPLETED) continue

            if (q.questId == "q_alchemist"){
                copy[i] = updateAlchemist(q, event)
            }
            if (q.questId == "q_guard"){
                copy[i] = updateGuard(q, event)
            }
            if (q.questId == "q_bigBabJohn"){
                copy[i] = updateJohn(q, event)
            }

        }
        return copy.toList()
    }

    private fun updateAlchemist(q: QuestStateOnServer, event: GameEvent): QuestStateOnServer{
        if(q.step == 0 && event is QuestBranchChosen && event.questId == q.questId){
            return when (event.branch){
                QuestBranch.HELP -> q.copy(
                    step = 1,
                    branch = QuestBranch.HELP,
                    progressCurrent = 0,
                    progressTarget = 3,
                    isNew = false
                )
                QuestBranch.THREAT -> q.copy(
                    step = 1,
                    branch = QuestBranch.THREAT,
                    progressCurrent = 0,
                    progressTarget = 10,
                    isNew = false
                )
                else -> q
            }
        }
        if (q.step == 1 && q.branch == QuestBranch.HELP && event is ItemCollected && event.itemID == "Herb"){
            val newCurrent = (q.progressCurrent + event.countAdded) .coerceAtMost(q.progressTarget)
            val update = q.copy(progressCurrent = newCurrent, isNew = false)

            if (newCurrent >= q.progressTarget){
                return update.copy(step =2, progressCurrent = 0, progressTarget = 0)
            }
            return update
        }
        if (q.step == 1 && q.branch == QuestBranch.THREAT && event is GoldTurnedIn && event.questId == q.questId){
            val newCurrent = (q.progressCurrent + event.amount) .coerceAtMost(q.progressTarget)
            val update = q.copy(progressCurrent = newCurrent, isNew = false)

            if (newCurrent >= q.progressTarget){
                return update.copy(step =2, progressCurrent = 0, progressTarget = 0)
            }
            return update
        }
        return q
    }

    private fun updateJohn(q: QuestStateOnServer, event: GameEvent): QuestStateOnServer{
        if(q.step == 0 && event is QuestBranchChosen && event.questId == q.questId){
            return when (event.branch){
                QuestBranch.LISTEN -> q.copy(
                    step = 1,
                    branch = QuestBranch.LISTEN,
                    progressCurrent = 0,
                    progressTarget = 3,
                    isNew = false
                )
                QuestBranch.DENY -> q.copy(
                    step = 1,
                    branch = QuestBranch.DENY,
                    progressCurrent = 0,
                    progressTarget = 10,
                    isNew = false
                )
                else -> q
            }
        }
        if (q.step == 1 && q.branch == QuestBranch.LISTEN && event is ItemCollected && event.itemID == "speaker"){
            val newCurrent = (q.progressCurrent + event.countAdded) .coerceAtMost(q.progressTarget)
            val update = q.copy(progressCurrent = newCurrent, isNew = false)

            if (newCurrent >= q.progressTarget){
                return update.copy(step =2, progressCurrent = 0, progressTarget = 0)
            }
            return update
        }
        if (q.step == 1 && q.branch == QuestBranch.DENY && event is RunJohn && event.questId == q.questId){
            val newCurrent = (q.progressCurrent + event.amount) .coerceAtMost(q.progressTarget)
            val update = q.copy(progressCurrent = newCurrent, isNew = false)

            if (newCurrent >= q.progressTarget){
                return update.copy(step =2, progressCurrent = 0, progressTarget = 0)
            }
            return update
        }
        return q
    }

    private fun updateGuard(q: QuestStateOnServer, event: GameEvent): QuestStateOnServer{
        val base = if (q.step == 0){
            q.copy(step = 1, progressCurrent = 0, progressTarget = 5, isNew = false)
        }else q

        if (base.step == 1 && event is GoldTurnedIn && event.questId == base.questId){
            val newCurrent = (base.progressCurrent + event.amount).coerceAtMost(base.progressTarget)
            val updated = base.copy(progressCurrent = newCurrent, isNew = false)

            if (newCurrent >= base.progressTarget){
                return updated.copy(step = 3, progressCurrent = 0, progressTarget = 0)
            }
            return updated
        }
        return base
    }
}

class GameServer{
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _commands = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<GameCommand> = _commands.asSharedFlow()

    fun trySend(cmd: GameCommand): Boolean = _commands.tryEmit(cmd)

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to PlayerData("Oleg", 0, emptyMap()),
            "Stas" to PlayerData("Stas", 0, emptyMap())
        )
    )

    val players: SharedFlow<Map<String, PlayerData>> = _players.asSharedFlow()

    private val _questsByPlayer = MutableStateFlow(
        mapOf(
            "Oleg" to initialQuestList(),
            "Stas" to initialQuestList()
        )
    )

    val questByPlayer: StateFlow<Map<String, List<QuestStateOnServer>>> = _questsByPlayer.asStateFlow()

    fun start(scope: kotlinx.coroutines.CoroutineScope, questSystem: QuestSystem){
        scope.launch {
            commands.collect{ cmd ->
                processCommand(cmd, questSystem)
            }
        }
    }

    private fun setPlayerData(playerId: String, data: PlayerData){
        val map = _players.value.toMutableMap()
        map[playerId] = data
        _players.value = map.toMap()
    }
    private fun getPlayerData(playerId: String): PlayerData{
        return _players.value[playerId] ?: PlayerData(playerId, 0, emptyMap())
    }
    private fun setQuests(playerId: String, quests: List<QuestStateOnServer>){
        val map = _questsByPlayer.value.toMutableMap()
        map[playerId] = quests
        _questsByPlayer.value = map.toMap()
    }
    private fun getQuests(playerId: String): List<QuestStateOnServer>{
        return _questsByPlayer.value[playerId] ?: emptyList()
    }
    private suspend fun processCommand(cmd: GameCommand, questSystem: QuestSystem){
        when(cmd){
            is CmdOpenQuest -> {
                val list = getQuests(cmd.questId).toMutableList()

                for (i in list.indices){
                    if (list[i].questId == cmd.questId){
                        list[i] = list[i].copy(isNew = false)
                    }
                }
                setQuests(cmd.playerId, list)
                _events.emit(QuestJournalUpdated(cmd.playerId))
            }
            is CmdPinQuest -> {
                val list = getQuests(cmd.questId).toMutableList()

                for (i in list.indices){
                    if (list[i].questId == cmd.questId){
                        list[i] = list[i].copy(isPinned = false)
                    }
                }
                setQuests(cmd.playerId, list)
                _events.emit(QuestJournalUpdated(cmd.playerId))
            }
            is CmdChooseBranch -> {
                val quests = getQuests(cmd.playerId)
                val target = quests.find { it.questId == cmd.questId }

                if (target == null){
                    _events.emit(ServerMessage(cmd.playerId, "Квест ${cmd.questId} не найден"))
                    return
                }
                if (target.status != QuestStatus.ACTIVE){
                    _events.emit(ServerMessage(cmd.playerId, "Квест ${cmd.questId} сейсем не активен"))
                }

                val ev = QuestBranchChosen(cmd.playerId, cmd.questId, cmd.branch)
                _events.emit(ev)

                val updated = questSystem.applyEvent(quests, ev)
                setQuests(cmd.playerId, updated)

                _events.emit(QuestJournalUpdated(cmd.playerId))

            }
            is CmdGiveGoldDebug -> {
                val player = getPlayerData(cmd.playerId)
                setPlayerData(cmd.playerId, player.copy(gold = player.gold + cmd.amount))
                _events.emit(ServerMessage(cmd.playerId, "Выдано золота + ${cmd.amount}"))
            }
            is CmdTurnInGold -> {
                val player = getPlayerData(cmd.playerId)

                if (player.gold < cmd.amount){
                    _events.emit(ServerMessage(cmd.playerId, "Недостаточно богат, нужно ${cmd.amount}"))
                    return
                }

                setPlayerData(cmd.playerId, player.copy(gold = player.gold - cmd.amount))

                val ev = GoldTurnedIn(cmd.playerId, cmd.questId, cmd.amount)
                _events.emit(ev)

                val updated = questSystem.applyEvent(getQuests(cmd.playerId), ev)
                setQuests(cmd.playerId, updated)

                _events.emit(QuestJournalUpdated(cmd.playerId))
            }

            is CmdFinishQuest -> {
                finishQuest(cmd.playerId, cmd.questId)
            }
            else -> {}
        }
    }

    private suspend fun finishQuest(playerId: String, questId: String){
        val list = getQuests(playerId).toMutableList()

        val index = list.indexOfFirst { it.questId == questId }

        if (index == -1){
            _events.emit(ServerMessage(playerId, "Квест $questId не найдет"))
            return
        }

        val q = list[index]

        if (q.status != QuestStatus.ACTIVE){
            _events.emit(ServerMessage(playerId, "Нельзя завершить $questId статус: ${q.status}"))
            return
        }

        if (q.step != 2){
            _events.emit(ServerMessage(playerId, "Нельзя завершить квест $questId - сначала дойди до этапа 2"))
            return
        }

        list[index] = q.copy(
            status = QuestStatus.COMPLETED,
            step = 3,
            isNew = false
        )

        setQuests(playerId, list)
        _events.emit(QuestCompleted(playerId, questId))

        unlockDependentQuests(playerId, questId)

        _events.emit(QuestJournalUpdated(playerId))
    }

    private suspend fun unlockDependentQuests(playerId: String, completedQuestId: String){
        val list = getQuests(playerId).toMutableList()
        var changed = false

        for(i in list.indices){
            val q = list[i]

            if(q.status == QuestStatus.LOCKED && q.unlockRequiredQuestId == completedQuestId){
                list[i] = q.copy(
                    status = QuestStatus.ACTIVE,
                    isNew = true
                )
                changed = true

                _events.emit(QuestUnlocked(playerId, q.questId))
            }
        }
        if(changed){
            setQuests(playerId, list)
        }
    }
}

fun initialQuestList(): List<QuestStateOnServer>{
    return listOf(
        QuestStateOnServer(
            "q_alchemist",
            "Помочь Джесси",
            QuestStatus.ACTIVE,
            0,
            QuestBranch.NONE,
            0,
            0,
            true,
            false,
            null
        ),
        QuestStateOnServer(
            "q_guard",
            "Дать взятку",
            QuestStatus.LOCKED,
            0,
            QuestBranch.NONE,
            0,
            0,
            false,
            false,
            "q_alchemist"
        ),
        QuestStateOnServer(
            "q_bigBabJohn",
            "Послушать музыку",
            QuestStatus.LOCKED,
            0,
            QuestBranch.NONE,
            0,
            0,
            false,
            false,
            "q_guard"
        )
    )
}

class HudState{
    val activePlayerIdFlow = MutableStateFlow("Oleg")
    val activePlayerIdUi = mutableStateOf("Oleg")

    val gold = mutableStateOf(0)
    val inventoryText = mutableStateOf("Inventory(empty)")

    val questEntries = mutableStateOf<List<QuestJournalEntry>>(emptyList())
    val selectedQuests = MutableStateFlow<String?>(null)

    val log = mutableStateOf<List<String>>(emptyList())
}

fun hudLog(hud: HudState, line: String){
    hud.log.value = (hud.log.value + line).takeLast(20)
}

fun markerSymbol(marker: QuestMarker): String{
    return when(marker){
        QuestMarker.NEW -> "!"
        QuestMarker.PINNED -> "📌"
        QuestMarker.COMPLETED -> "✅"
        QuestMarker.LOCKED -> "🔒"
        QuestMarker.NONE -> "🌴"
    }
}

fun journalSortRank(entry: QuestJournalEntry): Int{
    return when{
        entry.marker == QuestMarker.PINNED -> 0
        entry.marker == QuestMarker.NEW -> 1
        entry.status == QuestStatus.ACTIVE -> 2
        entry.status == QuestStatus.LOCKED -> 3
        entry.status == QuestStatus.COMPLETED -> 4
        else -> 5
    }
}

fun eventToText(event: GameEvent): String{
    return when(event){
        is QuestBranchChosen -> "QuestBranchChosen ${event.questId} -> ${event.branch}"
        is ItemCollected -> "ItemCollected ${event.itemID} x ${event.countAdded}"
        is GoldTurnedIn -> "GoldTurnedIn ${event.questId} - ${event.amount}"
        is QuestCompleted -> "QuestCompleted ${event.questId}"
        is QuestUnlocked -> "QuestUnlocked ${event.questId}"
        is QuestJournalUpdated -> "QuestJournalUpdated ${event.playerId}"
        is ServerMessage -> "Server ${event.text}"
        else -> ""
    }
}

// 1.1 Я люблю Encore
// 1.2 b) и c)
// 1.3 ответ равен =    (∫₀^{π} e^(sin x) * cos(x) dx)² + (∑_{n=1}^{∞} (ζ(2n) - 1) / n) * (Γ(7) * φ^(√(144)) -
//                      ∏_{p prime ≤ 100} (p² - 1)/(p² + 1)) + lim_{x→0} ( (sin x)/x )^(1/x²) * (∫_{-∞}^{∞}
//                      e^(-x²) dx)⁴ + (∂/∂t [Li₂(t) + Li₃(t)] при t=1/2) * (∮_{|z|=2} (e^(1/z) / (z-1)) dz) -
//                      (√(163) * π * e^(π√(67)) ) / (ln(640320)³) + (∫_0^1 ∫_0^1 (x^y + y^x) dx dy) *
//                      (∑_{k=1}^{1000} k² / k!) - (lim_{n→∞} (1 + 1/n)^n) * (∫_{0}^{∞} t²/(e^t - 1) dt) +
//                      (det [[23, 47, 89], [17, 53, 71], [13, 41, 97]]) * (∏_{k=1}^{5} (k⁴ - 6k³ + 11k² - 6k + 1)) +
//
//                      (∫_{0}^{1} ln(1/x) ln(1/(1-x)) dx) * (∑_{n=0}^{∞} (-1)^n/(2n+1)²) * 1728