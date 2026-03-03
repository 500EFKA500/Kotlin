package lesson1

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

class GameState{
    val playerId = mutableStateOf("Player")
    // создает состояние, за которым умеет наблюдать и меняться UI
    // Если состояние игрока (его hp) изменилось -> перерисует интерфейс

    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)
    val potionTicksLeft = mutableStateOf(0)
    // Тики - условные единицы измерения времени в игровом мире
    // У нас на примере будет 1 тик = 1 секунда
}
    fun main() = KoolApplication{
        // KoolApplication - запуск движка Kool
        val game = GameState()

        addScene {
            // Добавление сцены игровой
            defaultOrbitCamera()
            // Готовая камера - легко перемещается мышью по умолчанию

            // Добавление объекта на сцену
            addColorMesh { // Добавить цветной текстурируемый объект
                generate { // Генерация вершин фигуры
                    cube { // Пресет генерации - куб
                        colored() // автоматически создат разные цвета разным граням фигуры
                    }
                }
                shader = KslPbrShader{ // Назначение материала объекту
                    color { vertexColor() }
                    // Берем цвета для объекта из его плоскостей
                    metallic(0f) // Металлизация объекта
                    roughness(0.25f) // Шероховатость (0f - глянцевый / 1f - матовый)
                }

                onUpdate{
                    // метод который выполняется каждый кадр игры
                    transform.rotate(45f.deg * Time.deltaT, Vec3f.X_AXIS)
                    // rotate(угол, ось)
                    // * Time.deltaT - простая формула подсчета того, сколько прошло секунд
                }
            }
            lighting.singleDirectionalLight {
                setup(Vec3f(-1f, -1f, -1f)) // установить на сцену
                setColor(Color.WHITE, 5f)
                // setColor(цвет, сила) - включить свет
            }
            var potionTimerSec = 0f

            onUpdate{
                if(game.potionTicksLeft.value > 0){
                    potionTimerSec += Time.deltaT

                    if (potionTimerSec >= 1f){
                        potionTimerSec = 0f
                        game.potionTicksLeft.value = game.potionTicksLeft.value - 1

                        game.hp.value = (game.hp.value - 2).coerceAtLeast(0)
                    }
                }else{
                    potionTimerSec = 0f
                }
            }
        }

        addScene {
            setupUiScene(ClearColorLoad)

            addPanelSurface {
                modifier
                    .size(300.dp, 210.dp)
                    .align(AlignmentX.Start, AlignmentY.Top)
                    .padding(16.dp)
                    .background(RoundRectBackground(Color(0f,0f,0f,0.5f), 14.dp))

                Column{
                    // use() - прочитать состояние - подписаться на него и реагировать на его изменения
                    Text("Игрок: ${game.playerId.use()}"){}
                    Text("HP: ${game.hp.use()}"){}
                    Text("Gold: ${game.gold.use()}"){}
                    Text("Действие зелья: ${game.potionTicksLeft.use()}"){}
                }

                Row{
                    modifier.padding(12.dp)

                    Button("Урон hp-10"){
                        modifier
                            .padding(end = 8.dp)
                            // отступ не со всех сторон а только справа
                            .onClick{
                                game.hp.value = (game.hp.value - 10).coerceAtLeast(0)
                            }
                    }
                    Button("Gold + 5"){
                        modifier
                            .padding(end = 8.dp)
                            .onClick{
                                game.gold.value = (game.gold.value + 5)
                            }
                    }
                    Button("Наложить эффект + 5"){
                        modifier
                            .padding(end = 8.dp)
                            .onClick{
                                game.potionTicksLeft.value = (game.potionTicksLeft.value + 5)
                            }
                    }
                }
            }
        }
    }



















