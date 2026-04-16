import java.io.File

fun main() {
    val filePath = "kotlin/lesson1/main.kt" // Укажи нужный .kt файл здесь
    val lessonFile = File(filePath)

    require(lessonFile.exists() && lessonFile.isFile) {
        "Некорректный путь к файлу: $filePath"
    }

    val linesCount = lessonFile.useLines { it.count() }
    println(linesCount)
}
