import java.io.File

fun main() {
    print("Введите путь к файлу урока (.kt): ")
    val filePath = readlnOrNull()?.trim().orEmpty()

    if (filePath.isBlank()) {
        println("Путь к файлу не указан.")
        return
    }

    val lessonFile = File(filePath)

    if (!lessonFile.exists()) {
        println("Файл не найден: $filePath")
        return
    }

    if (!lessonFile.isFile) {
        println("Указанный путь не является файлом: $filePath")
        return
    }

    val linesCount = lessonFile.useLines { lines -> lines.count() }
    println("В файле '$filePath' строк: $linesCount")
}
