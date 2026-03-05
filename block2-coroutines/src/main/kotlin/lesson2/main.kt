package lesson2

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

enum class ItemType{
    WEAPON,
    ARMOR,
    POTION
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

class GameState(){
    val playerId = mutableStateOf("Player")
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)
    val potionTicksLeft = mutableStateOf(0)
    val hotbar = mutableStateOf(
        List<ItemStack?>(9) {null}
        // Создание списка предметов (максимум 9) по умолчанию ячейки пустые
    )
    val selectedSlot = mutableStateOf(0)
}

val HEALING_POTION = Item(
    "potion_heal",
    "Healing Potion",
    ItemType.POTION,
    12
)

val SWORD = Item(
    "sword",
    "Sword",
    ItemType.WEAPON,
    1
)

fun putIntoSlot(
    slots: List<ItemStack?>,
    slotIndex: Int,
    item: Item,
    addCount: Int
): List<ItemStack?>{
    val newSlots = slots.toMutableList()
    val current = newSlots[slotIndex]

    if (current == null){
        val count = minOf(addCount, item.maxStack)
        newSlots[slotIndex] = ItemStack(item, count)
        return newSlots
    }

    if(current.item.id == item.id && item.maxStack > 1){
        val freeSpace = item.maxStack - current.count
        val toAdd =minOf(addCount, freeSpace)
        newSlots[slotIndex] = ItemStack(item, current.count + toAdd)
        return newSlots
    }
    // Если в слоте другой передмет или максимальный стак заполнен - ничего не кладем
    return newSlots
}

fun useSelected(
    slots: List<ItemStack?>,
    slotIndex: Int
): Pair<List<ItemStack?>, ItemStack?> {
    // Pair - возвращает пару значений (1 новые слоты 2 то что использовали)

    val newSlots = slots.toMutableList()
    val current = newSlots[slotIndex] ?: return Pair(newSlots, null)

    val newCount = current.count - 1

    if (newCount <= 0) {
        // Если предметы кончились в слоте после использования - очистить ячейку
        newSlots[slotIndex] = null
    } else {
        newSlots[slotIndex] = ItemStack(current.item, newCount)
    }
    return Pair(newSlots, current)
}

fun main() = KoolApplication{
    val game = GameState()

    // Сцена мировая 3D
    addScene {
        defaultOrbitCamera()

        addColorMesh {
            generate {
                cube{ colored() }
            }

            shader = KslPbrShader{
                color { vertexColor() }
                metallic(0.8f)
                roughness(0.5f)
            }

            onUpdate{
                transform.rotate(45f.deg * Time.deltaT, Vec3f.X_AXIS)
            }
        }
        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }

        var potionTimeSec = 0f
        onUpdate{
            if (game.potionTicksLeft.value > 0){
                potionTimeSec += Time.deltaT
                if (potionTimeSec >= 1f){
                    potionTimeSec = 0f
                    game.potionTicksLeft.value -= 1
                    game.hp.value = (game.hp.value - 2).coerceAtLeast(0)
                }
            }else{
                potionTimeSec = 0f
            }
        }
    }

    // Сцена худа
    addScene {
        setupUiScene(ClearColorLoad)

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f,0f,0f, 0.6f), 14.dp))
                .padding(12.dp)

            Column {
                Text("Игрок: ${game.playerId.use()}"){}
                Text("HP: ${game.hp.use()}"){}
                Text("Отравление: ${game.potionTicksLeft.use()}"){}
            }
            Row {
                modifier.margin(top = 6.dp)
                val slots = game.hotbar.use()
                val select = game.selectedSlot.use()

                for (i in 0 until 9){
                    // Рисуем каждую ячейку инвентаря
                    val isSelected = (i == select)
                    Box {
                        modifier
                            .size(44.dp, 44.dp)
                            .margin(end = 5.dp)
                            .background(
                                RoundRectBackground(
                                    if (isSelected) Color(0.2f, 0.6f, 1f, 0.8f) else Color(0f,0f,0f, 0.35f),
                                    8.dp
                                )
                            )

                            .onClick{
                                game.selectedSlot.value = i
                            }

                        val stack = slots[i]
                        if(stack == null){
                            Text(" "){}
                        }else{
                            Column {
                                modifier.padding(6.dp)

                                Text("${stack.item.name}"){
                                    modifier.font(sizes.smallText)
                                }

                                Text("x${stack.count}"){
                                    modifier.font(sizes.smallText)
                                }
                            }
                        }
                    }
                }
            }

            Row {
                modifier.margin(top = 6.dp)

                Button("Получить зелье"){
                    modifier
                        .margin(end = 8.dp)
                        .onClick{
                            val idx = game.selectedSlot.value
                            val updated = putIntoSlot(game.hotbar.value, idx, HEALING_POTION, 6)
                            game.hotbar.value = updated
                        }
                }

                Button("Получить меч"){
                    modifier
                        .margin(end = 8.dp)
                        .onClick{
                            val idx = game.selectedSlot.value
                            val updated = putIntoSlot(game.hotbar.value, idx, SWORD, 1)
                            game.hotbar.value = updated
                        }
                }

                Button("Использовать выбранное"){
                    modifier.onClick{
                        val idx = game.selectedSlot.value
                        val (updatedSlots, used) = useSelected(game.hotbar.value, idx)
                        game.hotbar.value = updatedSlots

                        // Если использовали зелье - наложить лечение
                        if(used != null && used.item.type == ItemType.POTION){
                            game.hp.value = (game.hp.value + 20).coerceAtMost(100)
                        }
                    }
                }
            }

            Row {
                modifier.margin(top = 6.dp)

                Button("Наложить яд"){
                    modifier.onClick{
                        game.potionTicksLeft.value += 5
                    }
                }
            }
        }
    }
}
// Сделать так, чтобы при выборе меча в активном столе инвентаря - появлялся модификатор урона
// То есть создать свойство базового урона
// Создать логику проверки меча в активном слоте
// Создать 2 вид меча который будет делать больший модификатор
// Вывести активное состояние урона игрока на UI
// Итоговый пример:
// - базовый урон = 20
// - если выбран обычный меч, отобразить на панели HUD 20 + 10
// - если выбран легендарный меч отобразитьб 20 + 20














