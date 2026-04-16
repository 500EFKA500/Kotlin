package strokeCounter

import java.io.File

fun main() {
    val candidatePaths = listOf(
        "kotlin/lesson1/main.kt",
        "src/main/kotlin/lesson1/main.kt",
        "lesson1/main.kt"
    )

    val lessonFile = candidatePaths
        .asSequence()
        .map(::File)
        .firstOrNull { it.exists() && it.isFile }
        ?: error("Не удалось найти файл урока. Проверены пути: ${candidatePaths.joinToString()}")

    val linesCount = lessonFile.useLines { it.count() }
    println(linesCount)
}