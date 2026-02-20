package lesson2

import de.fabmax.kool.KoolApplication   // Подключение основной библиотеки Kool
import de.fabmax.kool.addScene

import de.fabmax.kool.math.*        // Vec3f - 3D-вектор (x y z) координаты мира
import de.fabmax.kool.scene.*      // Scene - сцена (мир) куда и будут добовляться объкты
import de.fabmax.kool.util.Time         // Time.deltaT - время между кадпрами
import de.fabmax.kool.util.Color         // Цвет интерфесов
import de.fabmax.kool.modules.ksl.KslPbrShader  // Шейдер PBR - в роли материалов у обьектов
import de.fabmax.kool.modules.ui2.*     // Компоненты кнопок, текста, колонок, полей
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.scene.defaultOrbitCamera

//Урок 2 - hotbar инвентарь
enum class ItemType{
    WEAPON,
    ARMOR,
    POTION
}

data class Item(
    val id: String,
    val name: String,
    val type: ItemType,
    val maxStack: Int,
    val bonusDamage: Int
)
data class ItemStack(
    val item: Item,
    val count: Int
)

class gameState{
    val baseDamage: Int = 10
    val playerId = mutableStateOf("Player")
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)
    val damage = mutableStateOf(baseDamage)
    val hotbar = mutableStateOf(
        List<ItemStack?>(9) {null}
        //список из 9 пустых ячеек хотбара
    )
    val selectedSlot = mutableStateOf(0)
    val potionTicksLeft = mutableStateOf(0)
}

val HEALING_POTION = Item(
    "potion_heal",
    "Healing Potion",
    ItemType.POTION,
    12,
    0
)
val WOOD_SWORD = Item(
    "sword_wood",
    "Wood Sword",
    ItemType.WEAPON,
    1,
    10
)

fun putIntoSlot(
    slots: List<ItemStack?>,
    slotsIndex: Int,
    item: Item,
    addCount: Int
) : List<ItemStack?>{
    val newSlots = slots.toMutableList()
    val current = newSlots[slotsIndex]

    if (current == null) {
        val count = minOf(addCount, item.maxStack)
        newSlots[slotsIndex] = ItemStack(item, count)
        return newSlots
    }

    // если слот не пуст - стакаем в него предметы, только если это тот же предмет и не максимум предметов
    if (current.item.id == item.id && item.maxStack > 1){
        val freeSpace = item.maxStack - current.count
        val toAdd = minOf(addCount, freeSpace)
        newSlots[slotsIndex] = ItemStack(item, current.count + toAdd)
        return  newSlots
    }


    // если предмет другой, или макс число стака равно одному - то не кладем предмет
    // на данной релизации пока что просто будет надпись "не получилось"
    return newSlots
}

fun useSelected(
    slots: List<ItemStack?>,
    slotsIndex: Int
) : Pair<List<ItemStack?>, ItemStack?>{
    // Pair - создает пару значений (новые слоты, и что использовали в слотах)
    val newSlots = slots.toMutableList()
    val current = newSlots[slotsIndex] ?: return Pair(newSlots,null)

    val newCount = current.count - 1

    if (newCount <= 0){
        //Если слот после использования предмета стал пуст
        newSlots[slotsIndex] = null
    }else{
        newSlots[slotsIndex] = ItemStack(current.item, newCount)
        //Если после использования предмета стак не закончился - обновляем стак
    }

    return Pair(newSlots, current)
}

// ЛОГИКА ДЛЯ ПОСЧЕТА УРОНА ОТ ЯДА
var potionTimeSec = 0f
//Счетчик действия яда на нас


fun main() = KoolApplication{
    val game = gameState()

    addScene { // создание игровой сцены (мира)
        defaultOrbitCamera()
        // Готовая камера, по умолчание можно крутить мышкой вокруг объккта, управлять ею при событиях и тд

        // Добовляем на сцену куб
        addColorMesh { // Меш - модель (с цветом)
            generate { // генерация геометрии модели
                cube { // Создание куба
                    colored() // создание раскраски куба по его вершинам
                }
            }

            shader = KslPbrShader { // с помощью шейдера мы назначаем материал или цвет
                color { vertexColor() }
                metallic(0f)    // Эффект металла на поверхности
                roughness(0.25f)             // [РАФНЕС] - шереховатость (0 = глянец, 1 = матовость)
            }

            onUpdate{
                // onUpdate - вызывать тело {...} каждый кадр
                // TimeDeltaT - секунды между кадрами (важно использовать дельту, чтобы скорость, урон,
                // порядок действий и тд - было равным у всех (игроков, существ) на разном FPS

                transform.rotate(45f.deg * Time.deltaT, Vec3f.X_AXIS)
                // rotate(угол, ось) - метод вращения обьекта
                // 45f.deg - 45 градусов в секунду
                // * Time.deltaT - формула подсчета "сколько прошло секунд"
                // Vec3f.X_AXIS - обозначение оси X в трехмерном пространстве
            }
        }

        // Свет (без него не будет видна текстура)
        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
            //
        }

        // ЛОГИКА ДЛЯ ПОДСЧЕТА УРОНА ОТ ЯДА

        var potionTimeSec = 0f
        // Счетчик действия яда на нас

        onUpdate{
            if (game.potionTicksLeft.value > 0){
                // .valuse - достаем текущее значение state
                potionTimeSec += Time.deltaT
                // Накапливаем секунды
                if (potionTimeSec >= 1f){
                    // Прошло больше или ровно 1 сек тогда -> выполняем тик (накладываем урон от яда)
                    potionTimeSec = 0f
                    game.potionTicksLeft.value = game.potionTicksLeft.value - 1
                    // Уменшаем количество тиков действия яда

                    game.hp.value = (game.hp.value - 2).coerceAtLeast(0)
                    // Отнимаем 2 hp но не даем упасьб меньше 0
                }
            }else{
                potionTimeSec = 0f
                // Если яда больше нет сбрасываем таймер
            }
        }
    }

    addScene {
        setupUiScene(ClearColorLoad)
        // setupUiScene - превращение сцены в UI сцену (то есть все параметры будут настроены под интерфейс)
        // ClearColorLoad - КРИТИЧНО - не очищать экран, остовлять картинку мира под UI
        addPanelSurface {
            // Создание панели на UI экране - хоста
            modifier

                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                // align - выравнивание панели на экране
                .padding(16.dp)
                // padding - внутренний отступ
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))

            Column {
                Text("PLayer: ${game.playerId.use()}"){} // Обновляем имя игрока, если оно изменится
                Text("HP: ${game.hp.use()}"){}
                Text("Gold: ${game.gold.use()}"){}
                Text("Potions${game.potionTicksLeft.use()}"){}
                Text("Damage: ${
                    if (game.damage.value == game.baseDamage){
                        game.damage
                    }else{
                    game.baseDamage 
                }}"){}

                modifier.padding(10.dp)
                Row {
                    modifier.margin(top = 6.dp)

                    val slots = game.hotbar.use()
                    // Выводим обновлямый хот-бар на интерфейсе
                    val selected = game.selectedSlot.use()

                    for (i in 0 until 9){
                        // Рисуем слоты
                        val isSelected = (i == selected)
                        // Box - контейнер (не путать с панелью)
                        Box {
                            modifier
                                .size(44.dp, 44.dp)
                                .margin(end = 6.dp)
                                .background(
                                    RoundRectBackground(
                                        if (isSelected) Color (0.2f,0.6f,1f, 0.8f) else Color(0f, 0f, 0f, 0.8f),
                                        8.dp
                                    )
                                )
                                .onClick{
                                    game.selectedSlot.value = i
                                }
                            val stack = slots[i]
                            // значение слота = имя предмета + количетсво предметов в слоте
                            if (stack == null){
                                Text(""){}
                            } else {
                                Column {
                                    modifier.padding(6.dp)

                                    Text(stack.item.name){
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

                    Button("Наложить эффект"){
                        modifier
                            .margin(end = 8.dp)
                            .onClick{
                                val idx = game.selectedSlot.value
                                val updated = putIntoSlot(game.hotbar.value, idx, HEALING_POTION, 1)
                                game.hotbar.value = updated
                            }
                    }
                    Button("Дать меч"){
                        modifier
                            .margin(end = 8.dp)
                            .onClick{
                                if (game.selectedSlot.value == WOOD_SWORD.maxStack){
                                    println("Знаешь, что я так подумал, много хочешь")
                                }else{
                                    val idx = game.selectedSlot.value
                                    val updated = putIntoSlot(game.hotbar.value, idx, WOOD_SWORD, 1)
                                    game.hotbar.value = updated
                                }
                            }
                    }
                    Button("Использовать предмет"){
                        modifier.onClick{
                            val idx = game.selectedSlot.value
                            val (updatedSlots, used) = useSelected(game.hotbar.value, idx)
                            game.hotbar.value = updatedSlots

                            if (used != null && used.item.type == ItemType.POTION){
                                game.hp.value = (game.hp.value + 20).coerceAtMost(100)
                            }
                            if (used != null && used.item.type == ItemType.WEAPON){
                                game.damage.value = (game.damage.value + used.item.bonusDamage)
                            }
                        }
                    }
                }

                Row {
                    modifier.margin(top = 6.dp)

                    Button("Poison +5") {
                        modifier.onClick {
                            game.potionTicksLeft.value = game.potionTicksLeft.value + 5
                        }
                    }
                }
            }
        }
    }
}