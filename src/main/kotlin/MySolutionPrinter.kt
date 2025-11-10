import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.ortools.sat.CpSolverSolutionCallback
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.ceil

// 1. Create a class that inherits from CpSolverSolutionCallback
class MySolutionPrinter(
    private val bounds: bounds,
    private val cordsIntVars: List<layoutItemAndIntVar>
) : CpSolverSolutionCallback() {

    // Use a default Gson for blueprints (Factorio JSON doesn't use underscores)
    val gson = GsonBuilder().create()
    var previousScore = Double.MAX_VALUE

    private var solutionCount = 0
    private val solutionLimit = 5 // Optional: stop after 5 solutions

    // 2. This is the magic function that the solver calls
    override fun onSolutionCallback() {
        solutionCount++
        val objectiveVal = objectiveValue()

        logs.add("\n--- Solution #$solutionCount Found! ---")
        logs.add("Objective Cost: ${objectiveVal}") // objectiveValue() is a built-in method
        logs.add("that's ${-1.0 + previousScore / objectiveVal} better")
        logs.add(printFormattedTime())
        logs.add("")
        previousScore = objectiveVal

        // 3. This is your printHumanOutput logic,
        //    moved inside the callback.
        //    Note: We call value() directly (it's part of this class)
        println("-------------------- LAYOUT --------------------")
        val emptySlot = " ".repeat(20) // Empty string of the same length
        (bounds.ly..bounds.uy).forEach { y ->
            val rowItems = (bounds.lx..bounds.ux).map { x ->
                // Find the assembler at this exact (x, y) coordinate
                val assembler = cordsIntVars.find {
                    value(it.cords.x) == x && value(it.cords.y) == y
                }

                assembler?.layoutItem?.item?.name?.formatToLength(20) ?: emptySlot
            }
            println("| " + rowItems.joinToString(" | ") + " |")
        }
        println("------------------------------------------------\n")

        // 4. Optional: Tell the solver to stop if you're happy
        // if (solutionCount >= solutionLimit) {
        //     stopSearch()
        // }

        // --- 2. Create the Blueprint ---
        val entities = mutableListOf<BlueprintEntity>()
        var entityNumber = 1 // Start entity numbering at 1

        // Define the spacing for each macro-tile
        // Your 5x3 tile needs a bit of space. Let's use 6x4.
        val TILE_WIDTH = 5.0
        val TILE_HEIGHT = 4.0

        for (layoutItem in cordsIntVars) {
            // Get the solver's macro-grid position
            val solverX = value(layoutItem.cords.x).toDouble()
            val solverY = value(layoutItem.cords.y).toDouble()

            // Calculate the top-left corner of this tile
            // (solverX - 1) maps the solver's [1..N] grid to a [0..N-1] index
            val baseX = (solverX - 1) * TILE_WIDTH
            val baseY = (solverY - 1) * TILE_HEIGHT

            // Get the recipe for this assembler
            val recipeName = layoutItem.layoutItem.recipe.name

            // --- Add the 7 entities for your tile ---
            // (Using Factorio's centered coordinate system)
            // Your grid:
            // a | a | a | i | r  (y=0)
            // a | a | a | i | p  (y=1)
            // a | a | a | e | e  (y=2)

            val recipe = layoutItem.layoutItem.recipe

            val requestFilters = recipe.ingredients.mapIndexed { index, ingredient ->
                val requestedAmount = ceil(ingredient.amount / recipe.energy * 4).toInt()
                RequestFilter(
                    index = index + 1, // Factorio indices are 1-based
                    name = ingredient.name,
                    count = requestedAmount
                )
            }

            // 1. Assembler (a) (3x3, covers 0,0 to 2,2. Center is 1.5, 1.5)
            entities.add(BlueprintEntity(
                entity_number = entityNumber++,
                name = "assembling-machine-2",
                recipe = recipeName,
                position = Position(baseX + 1.5, baseY + 1.5), // Centered in its 3x3 area
                direction = 1
            ))

            // 2. Requester Chest (r) (1x1, at tile pos [4,0]. Center is 4.5, 0.5)
            entities.add(BlueprintEntity(
                entity_number = entityNumber++,
                name = "logistic-chest-requester",
                position = Position(baseX + 4.5, baseY + 0.5),
                request_filters = requestFilters, // **FIXED: Use 'request_filters' **
                request_from_buffers = true      // **FIXED: Add 'request_from_buffers' **
            ))

            // 3. Provider Chest (p) (1x1, at tile pos [4,1]. Center is 4.5, 1.5)
            entities.add(BlueprintEntity(
                entity_number = entityNumber++,
                name = "logistic-chest-passive-provider",
                position = Position(baseX + 4.5, baseY + 1.5)
            ))

            // 4. Top Inserter (i) (Input) (1x1, at tile pos [3,0]. Center is 3.5, 0.5)
            entities.add(BlueprintEntity(
                entity_number = entityNumber++,
                name = "fast-inserter",
                position = Position(baseX + 3.5, baseY + 0.5),
                direction = 2 // Points West (picks from East, drops to West)
            ))

            // 5. Bottom Inserter (i) (Output) (1x1, at tile pos [3,1]. Center is 3.5, 1.5)
            entities.add(BlueprintEntity(
                entity_number = entityNumber++,
                name = "fast-inserter",
                position = Position(baseX + 3.5, baseY + 1.5),
                direction = 6 // Points East (picks from West, drops to East)
            ))

            val pole1EntityNumber = (0..entityNumber step 6).toList()
            // 6. Pole 1 (e) (1x1, at tile pos [3,2]. Center is 3.5, 2.5)
            entities.add(BlueprintEntity(
                entity_number = entityNumber++,
                name = "medium-electric-pole", // Changed to medium for better coverage
                position = Position(baseX + 3.5, baseY + 2.5),
                neighbours = pole1EntityNumber // **Manually connect to Pole 1**
            ))

        }

        // Create the final object
        val blueprint = Blueprint(
            entities = entities,
            icons = listOf(Icon(Signal("item", "utility-science-pack"), 1)),
            label = "cum"
        )
        val root = BlueprintRoot(blueprint)

        // --- 3. Serialize, Compress, and Encode ---

        val jsonString = gson.toJson(root)
//        File("output_json.json").writeText(jsonString)

        val blueprintString = compressAndEncode(jsonString)

        File("$path/best_output_string.txt").writeText(blueprintString)
        File("$path/allSolves/${printFormattedTime()} - output_string_$solutionCount.txt").writeText(blueprintString)

    }
}


