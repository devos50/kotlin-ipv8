package nl.tudelft.ipv8.automation

import kotlinx.serialization.Serializable

@Serializable
data class Automation(val fixedValues: Map<String, String>, val figures: List<Figure>)

@Serializable
data class Figure(
    val name: String,
    val fixedValues: Map<String, String>,
    val tests: List<Test>,
    val iteratorDistributions: List<String>? = null
)

@Serializable
data class Test(val gar: String)

/**
 * @return 1. the configuration per node, per test, per figure ; 2. the names of the figures
 */
fun generateConfigs(
    automation: Automation
): Pair<List<List<List<Map<String, String>>>>, List<String>> {
    val configurations = arrayListOf<MutableList<MutableList<Map<String, String>>>>()
    val figureNames = arrayListOf<String>()

    // global fixed values
    val batchSize = automation.fixedValues["batchSize"]!!
    val iteratorDistribution = automation.fixedValues["iteratorDistribution"]!!
    val maxTestSample = automation.fixedValues["maxTestSample"]!!
    val maxIterations = automation.fixedValues["maxIterations"]!!
    val optimizer = automation.fixedValues["optimizer"]!!
    val learningRate = automation.fixedValues["learningRate"]!!
    val momentum = automation.fixedValues["momentum"]!!
    val l2Regularization = automation.fixedValues["l2Regularization"]!!
    val communicationPattern = automation.fixedValues["communicationPattern"]!!
    val iterationsBeforeEvaluation = automation.fixedValues["iterationsBeforeEvaluation"]!!
    val iterationsBeforeSending = automation.fixedValues["iterationsBeforeSending"]!!
    val figures = automation.figures

    for (figure in figures) {
        configurations.add(arrayListOf())
        figureNames.add(figure.name)
        val dataset = figure.fixedValues["dataset"]!!
        val overrideMaxIterations = figure.fixedValues["maxIterations"]
        val behavior = figure.fixedValues["behavior"]!!
        val modelPoisoningAttack = figure.fixedValues["modelPoisoningAttack"]!!
        val numNodes = figure.fixedValues["numNodes"]!!.toInt()
        val numAttackers = figure.fixedValues["numAttackers"]!!
        val firstNodeSpeed = figure.fixedValues["firstNodeSpeed"]?.toInt() ?: 0
        val firstNodeJoiningLate = figure.fixedValues["firstNodeJoiningLate"]?.equals("true") ?: false
        val overrideIteratorDistribution = figure.iteratorDistributions
        val overrideBatchSize = figure.fixedValues["batchSize"]
        val overrideIteratorDistributionSoft = figure.fixedValues["iteratorDistribution"]
        val overrideCommunicationPattern = figure.fixedValues["communicationPattern"]
        val parsedNumAttackers = numAttackers.split("_")[1].toInt()

        for (test in figure.tests) {
            val gar = test.gar

            for (transfer in booleanArrayOf(true, false)) {
                if (gar == "bristle" && !transfer) {
                    // BRISTLE can only work with transfer learning; otherwise all layers except for its outputlayer will stay 0
                    continue
                }
                configurations.last().add(arrayListOf())
                for (node in 0 until numNodes) {
                    val finalDistribution = overrideIteratorDistribution?.get(node % overrideIteratorDistribution.size)
                        ?: overrideIteratorDistributionSoft ?: iteratorDistribution
                    val finalBehavior = if (node < numNodes - parsedNumAttackers) "benign" else behavior
                    val finalSlowdown =
                        if ((node == 0 && firstNodeSpeed == -1) || (node != 0 && firstNodeSpeed == 1)) "d2" else "none"
                    val finalJoiningLate = if (node == 0 && firstNodeJoiningLate) "n2" else "n0"
                    val configuration = mapOf(
                        Pair("dataset", dataset),

                        Pair("batchSize", overrideBatchSize ?: batchSize),
                        Pair("maxTestSamples", maxTestSample),
                        Pair("iteratorDistribution", finalDistribution),

                        Pair("optimizer", optimizer),
                        Pair("learningRate", learningRate),
                        Pair("momentum", momentum),
                        Pair("l2", l2Regularization),

                        Pair("maxIterations", maxIterations),
                        Pair("gar", gar),
                        Pair("communicationPattern", overrideCommunicationPattern ?: communicationPattern),
                        Pair("behavior", finalBehavior),
                        Pair("slowdown", finalSlowdown),
                        Pair("joiningLate", finalJoiningLate),
                        Pair("iterationsBeforeEvaluation", iterationsBeforeEvaluation),
                        Pair("iterationsBeforeSending", iterationsBeforeSending),

                        Pair("modelPoisoningAttack", modelPoisoningAttack),
                        Pair("numAttackers", numAttackers)
                    )
                    configurations.last().last().add(configuration + mapOf(Pair("transfer", if (transfer) "true" else "false")))
                }
            }
        }
    }
    return Pair(configurations, figureNames)
}
