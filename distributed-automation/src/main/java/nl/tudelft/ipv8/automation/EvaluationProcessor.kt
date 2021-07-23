package nl.tudelft.ipv8.automation

import mu.KotlinLogging
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.fixedRateTimer

private const val DATE_PATTERN = "yyyy-MM-dd_HH.mm.ss"
private val DATE_FORMAT = SimpleDateFormat(DATE_PATTERN, Locale.US)
private val logger = KotlinLogging.logger("EvaluationProcessor")

class EvaluationProcessor(
    fileDirectory: File,
    runner: String
) {
    private val configurationHeader = arrayOf(
        "name",
        "simulationIndex",
        "dataset",
        "optimizer",
        "learning rate",
        "momentum",
        "l2",

        "batchSize",
        "iteratorDistribution",
        "maxTestSamples",

        "gar",
        "communicationPattern",
        "behavior",
        "numEpochs",
        "slowdown",
        "joiningLate",
        "iterationsBeforeEvaluation",
        "iterationsBeforeSending",

        "local model poisoning attack",
        "#attackers"
    ).joinToString(",")

    @Transient
    private val configurationLines = arrayListOf(configurationHeader)

    private val evaluationHeader = arrayOf(
        "node",
        "environment",
        "(unused)",
        "elapsedTime",
        "epoch",
        "iteration",
        "accuracy",
        "f1",
        "precision",
        "recall",
        "gmeasure",
        "mcc",
        "score",
        "before or after averaging",
        "#peers included in current batch"
    ).joinToString(",")

    @Transient
    private val evaluationLines = arrayListOf(evaluationHeader)
    private val fileResults = File(fileDirectory, "evaluation-$runner-${DATE_FORMAT.format(Date())}.csv")
    private var fileMeta = File(fileDirectory, "evaluation-$runner-${DATE_FORMAT.format(Date())}.meta.csv")
    private lateinit var currentName: String

    init {
        if (!fileDirectory.exists()) {
            fileDirectory.mkdirs()
        }
        fileResults.createNewFile()
        fileMeta.createNewFile()
    }

    fun newSimulation(
        name: String,
        mlConfiguration: List<Map<String, String>>
    ) {
        this.currentName = name
        mlConfiguration.forEachIndexed { index, configuration ->
            val line = parseConfiguration(name, index, configuration)
            configurationLines.add(line)
        }

        PrintWriter(fileMeta).use { pw -> configurationLines.forEach(pw::println) }
    }

    private fun parseConfiguration(name: String, index: Int, configuration: Map<String, String>): String {
        return arrayOf(
            name,
            index.toString(),
            configuration["dataset"],
            configuration["optimizer"],
            configuration["learningRate"],
            configuration["momentum"] ?: "<null>",
            configuration["l2"],

            configuration["batchSize"],
            configuration["iteratorDistribution"]!!.replace(", ", "-"),
            configuration["maxTestSamples"],

            configuration["gar"],
            configuration["communicationPattern"],
            configuration["behavior"],
            configuration["maxIterations"],
            configuration["slowdown"],
            configuration["joiningLate"],
            configuration["iterationsBeforeEvaluation"] ?: "null",
            configuration["iterationsBeforeSending"] ?: "null",

            configuration["modelPoisoningAttack"],
            configuration["numAttackers"]
        ).joinToString(",")
    }

    fun call(peer: Int, evaluation: String) {
        evaluationLines.add("$peer, $evaluation")
            PrintWriter(fileResults).use { pw -> evaluationLines.forEach(pw::println) }
    }
}
