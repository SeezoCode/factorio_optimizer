import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.ortools.Loader
import com.google.ortools.sat.CpModel
import com.google.ortools.sat.CpSolver
import com.google.ortools.sat.CpSolverStatus
import com.google.ortools.sat.IntVar
import com.google.ortools.sat.LinearExpr
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.ceil
import kotlin.math.pow

val logs = Log()

const val solvesPath = "src/main/resources/solves/"
val path = "${solvesPath}$launchTime - solve"
const val scalingFactor = 1000L

fun main() {
    val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerTypeAdapter(Entity::class.java, EntityDeserializer())
        .registerTypeAdapterFactory(ListOrObjectAdapterFactory())
        .create()

    // --- LOAD RECIPES DATA ---
    val jsonFileName = "recipes.json"
    val jsonString: String? = RootData::class.java.getResourceAsStream(jsonFileName)
        ?.bufferedReader()
        ?.use { it.readText() }

    if (jsonString == null) {
        throw RuntimeException("Could not find $jsonFileName")
    }

    // --- LOAD CONFIGURATION DATA ---
    val configFileName = "config.json"
    val configString: String? = RootData::class.java.getResourceAsStream(configFileName)
        ?.bufferedReader()
        ?.use { it.readText() }

    if (configString == null) {
        throw RuntimeException("Could not find $configFileName")
    }

    val config = gson.fromJson(configString, RunConfiguration::class.java)

    // --- DIRECTORY SETUP ---
    try {
        Files.createDirectory(Paths.get(solvesPath))
    } catch (e: IOException) {}

    try {
        Files.createDirectory(Paths.get(path))
        Files.createDirectory(Paths.get("$path/allSolves"))
        Files.createDirectory(Paths.get("$path/simpleTableVisual"))
    } catch (e: IOException) {}

    val data = gson.fromJson(jsonString, RootData::class.java)
    interlinkProductsWithItems(data)

    println("\n--- Successfully Parsed JSON Data ---")
    println("Game Version: ${data.gameVersion}")
    println("Quality setting: ${config.quality}")

    val appropriate = listOf("crafting", "pressing", "intermediate-products", "electronics", "crafting-with-fluid", "advanced-crafting", "electronics-with-fluid")

    // 1. Filter Forbidden Recipes (Loaded from JSON)
    data.recipes = data.recipes.filter { recipe -> recipe.value.name !in config.forbiddenRecipes }

    data.recipes = data.recipes.filter { it.value.category in appropriate }
    println("Found ${data.recipes.size} valid recipes after filtering.")

    println("\n")

    val quality = config.quality

    // 2. Load Requirements (Targets) from JSON
    val requirementsList = config.targets.mapNotNull { target ->
        val recipe = data.recipes[target.name]
        if (recipe != null) {
            recipeReq(recipe, target.amount)
        } else {
            println("WARNING: Target recipe '${target.name}' not found in data.")
            null
        }
    }

    val x = requirements(requirementsList, data)

    // 3. Load Forbidden Places from JSON
    val forbiddenPlaces = config.forbiddenPlaces.map { cords(it.x, it.y) }.toMutableList()

    (x).requirementsHumanOutput

    val ms = x.recipes.fold(0) { acc, item -> acc + ceil(item.noAssemblingMachines).toInt() } + forbiddenPlaces.size

    val maxX = ceil(ms.toDouble().pow(.5)).toLong()
    val maxY = ceil((ms.toDouble() / maxX.toDouble())).toLong()

    val bound1 = bounds(1, maxX, 1, maxY)
    logs.add(bound1.toString())
    logs.add("total filled spaces: $ms")

    Loader.loadNativeLibraries()
    val model = CpModel()

    val sourceOffset = 25

    // 4. Load Layout of Sources from JSON
    // Note: In your original code, you used `maxY` for positioning.
    // Since `maxY` is calculated dynamically, the JSON needs explicit coordinates,
    // or you must manually adjust the JSON Y values to match your grid size logic if needed.
    val layoutOfSources = config.sources.mapNotNull { source ->
        val item = data.items[source.name]
        if (item != null) {
            sourceItemLoc(item, cords(source.x, source.y), source.force)
        } else {
            println("WARNING: Source item '${source.name}' not found in data.")
            null
        }
    }

    layoutOfSources.forEach { logs.add("layoutOfSources: ${it.item.name}, (${it.cords.x}, ${it.cords.y}, force: ${it.force}") }

    logs.add(bound1.toString())
    val layoutOfRecipes = x.getLayoutItems(bound1, "x")

    val cordsIntVars = layoutOfRecipes.map {
        layoutItemAndIntVar(it, cords(model.newIntVar(it.bounds.lx, it.bounds.ux, "x_${it.id}"),
            model.newIntVar(it.bounds.ly, it.bounds.uy, "y_${it.id}")))
    }

    cordsIntVars.forEach { a ->  cordsIntVars.forEach { b -> if(a.cords != b.cords) {
        val b_x = model.newBoolVar("bx_${a.layoutItem.id}_${b.layoutItem.id}")
        model.addDifferent(a.cords.x, b.cords.x).onlyEnforceIf(b_x)
        val b_y = model.newBoolVar("by_${a.layoutItem.id}_${b.layoutItem.id}")
        model.addDifferent(a.cords.y, b.cords.y).onlyEnforceIf(b_y)
        model.addBoolOr(listOf(b_x, b_y))
    } }
    }

    cordsIntVars.forEach { item ->
        forbiddenPlaces.forEach { forbidden ->
            val bXDiff = model.newBoolVar("forbidden_x_${item.layoutItem.id.string}_${forbidden.x}_${forbidden.y}")

            model.addDifferent(item.cords.x, forbidden.x).onlyEnforceIf(bXDiff)
            model.addEquality(item.cords.x, forbidden.x).onlyEnforceIf(bXDiff.not())

            val bYDiff = model.newBoolVar("forbidden_y_${item.layoutItem.id.string}_${forbidden.x}_${forbidden.y}")

            model.addDifferent(item.cords.y, forbidden.y).onlyEnforceIf(bYDiff)
            model.addEquality(item.cords.y, forbidden.y).onlyEnforceIf(bYDiff.not())

            model.addBoolOr(listOf(bXDiff, bYDiff))
        }
    }

    val distances = cordsIntVars.map { consumer ->
        consumer.layoutItem.recipe.ingredients.map { ingredient ->
            val totalIngredientNeed = ingredient.amount * consumer.layoutItem.amount
            var ingredientNeedLeft = totalIngredientNeed
            val feasibleLayoutOptions = cordsIntVars.filter { f -> f.layoutItem.item == ingredient.item }

            val distancesToSources = (layoutOfSources.filter { it.item == ingredient.item || it.item == consumer.layoutItem.item }).groupBy { it.item }.let {
                it.keys.map { key ->
                    val sameSources = it[key]!!.map { source ->
                        val distXY = model.newIntVar(
                            0, (maxX + maxY + sourceOffset * 2),
                            "dist_src_${consumer.layoutItem.id.string}_${source.item.name}_${source.cords.x}_${source.cords.y}"
                        )
                        val distX =
                            model.newIntVar(0, maxX + sourceOffset, "dist_x_src_${consumer.layoutItem.id.string}_${source.item.name}")
                        val distY =
                            model.newIntVar(0, maxY + sourceOffset, "dist_y_src_${consumer.layoutItem.id.string}_${source.item.name}")

                        model.addAbsEquality(
                            distX,
                            LinearExpr.sum(arrayOf(consumer.cords.x, LinearExpr.constant(-source.cords.x)))
                        )
                        model.addAbsEquality(
                            distY,
                            LinearExpr.sum(arrayOf(consumer.cords.y, LinearExpr.constant(-source.cords.y)))
                        )
                        model.addEquality(distXY, LinearExpr.sum(arrayOf(distX, distY)))

                        distXY
                    }

                    val minSourceDist = model.newIntVar(0, (maxX + maxY + sourceOffset * sourceOffset), "min_dist_src_${consumer.layoutItem.id.string}_${ingredient.name}")
                    model.addMinEquality(minSourceDist, sameSources.toTypedArray())

                    val weightedCost = model.newIntVar(
                        0,
                        (maxX + maxY + sourceOffset * 2) * scalingFactor * ceil(totalIngredientNeed).toLong() * (it[key]?.first()?.force ?: 1.0).toInt(),
                        "min_dist_weighted_src_${consumer.layoutItem.id.string}_${key.name}"
                    )
                    model.addEquality(weightedCost, LinearExpr.term(minSourceDist, (totalIngredientNeed * scalingFactor * (it[key]?.first()?.force ?: 1.0)).toLong()))
                    weightedCost
                }
            }


            if (feasibleLayoutOptions.isNotEmpty()) print("consumer needs $totalIngredientNeed of ${ingredient.name} and has available: ")

            val distanceToProducers = feasibleLayoutOptions.mapNotNull { ingredientAss ->
                val assemblerIngredientProduction =
                    (ingredientAss.layoutItem.recipe.mainProduct?.amount?.div(ingredientAss.layoutItem.recipe.energy))
                        ?: 1.0

                val usageLeft = (1.0 - ingredientAss.layoutItem.usage).coerceAtLeast(0.0)
                val ingredientsLeftOnAss = usageLeft * assemblerIngredientProduction
                val ingredientsLeftAfterSatisfaction = (ingredientsLeftOnAss - ingredientNeedLeft).coerceAtLeast(0.0)
                val weight = ingredientsLeftOnAss - ingredientsLeftAfterSatisfaction
                ingredientNeedLeft -= weight
                ingredientAss.layoutItem.usage += weight / assemblerIngredientProduction
                print("{ total: $assemblerIngredientProduction (sat: $weight / $ingredientsLeftOnAss) } | ")

                if (weight > 0) {
                    val distX = model.newIntVar(
                        0,
                        maxX,
                        "dist_x_${consumer.layoutItem.id.string}_${ingredientAss.layoutItem.id.string}"
                    )
                    val distY = model.newIntVar(
                        0,
                        maxY,
                        "dist_y_${consumer.layoutItem.id.string}_${ingredientAss.layoutItem.id.string}"
                    )
                    val distXY =
                        model.newIntVar(
                            0,
                            maxX + maxY,
                            "dist_${consumer.layoutItem.id.string}_${ingredientAss.layoutItem.id.string}"
                        )

                    model.addAbsEquality(
                        distX,
                        LinearExpr.sum(arrayOf(consumer.cords.x, LinearExpr.term(ingredientAss.cords.x, -1)))
                    )

                    model.addAbsEquality(
                        distY,
                        LinearExpr.sum(arrayOf(consumer.cords.y, LinearExpr.term(ingredientAss.cords.y, -1)))
                    )

                    model.addEquality(distXY, LinearExpr.sum(arrayOf(distX, distY)))

                    val weightedCost = model.newIntVar(
                        0,
                        (maxX + maxY) * scalingFactor * ceil(weight).toLong(),
                        "dist_weighted_${consumer.layoutItem.id.string}_${ingredientAss.layoutItem.id.string}"
                    )
                    model.addEquality(weightedCost, LinearExpr.term(distXY, (weight * scalingFactor).toLong()))
                    weightedCost
                } else null
            }
            distanceToProducers + distancesToSources
        }
    }.flatten().flatten()

    val totalDistance = model.newIntVar(0, Int.MAX_VALUE.toLong()*10000000, "total_distance")
    model.addEquality(totalDistance, LinearExpr.sum((distances).toTypedArray()))

    model.minimize(totalDistance)

    val solver = CpSolver()
    val callback = MySolutionPrinter(bound1, cordsIntVars, data, quality)
    val status = solver.solve(model, callback)

    if (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE) {
        println("\n\n\nAnd finally:\n\n")
        cordsIntVars.printHumanOutput(bound1, solver)
    } else {
        println("No solution found.");
    }
}

// --- CONFIGURATION DATA CLASSES ---
data class RunConfiguration(
    val quality: String,
    val forbiddenRecipes: List<String> = emptyList(),
    val forbiddenPlaces: List<ConfigCords> = emptyList(),
    val targets: List<ConfigTarget>,
    val sources: List<ConfigSource> = emptyList()
)

data class ConfigCords(val x: Long, val y: Long)
data class ConfigTarget(val name: String, val amount: Double)
data class ConfigSource(val name: String, val x: Long, val y: Long, val force: Double = 1.0)


// --- EXISTING HELPERS ---

fun List<layoutItemAndIntVar>.printHumanOutput(bounds: bounds, solver: CpSolver) {
    println("-------------------- LAYOUT --------------------")
    val emptySlot = " ".repeat(tableCellWidth)
    (bounds.ly..bounds.uy).forEach { y ->
        val rowItems = (bounds.lx..bounds.ux).map { x ->
            val assembler = this.find {
                solver.value(it.cords.x) == x && solver.value(it.cords.y) == y
            }
            assembler?.layoutItem?.item?.name?.formatToLength(tableCellWidth) ?: emptySlot
        }
        println("| " + rowItems.joinToString(" | ") + " |")
    }
    println("------------------------------------------------\n")
}

data class layoutItemAndIntVar(val layoutItem: layoutItem, val cords:  cords<IntVar>)

private fun req.getLayoutItems(bounds: bounds, tag: String) = this.recipes.mapIndexed { i, it ->
    (1..ceil(it.noAssemblingMachines).toInt()).map {it2 -> layoutItem(bounds, it.recipe.mainProduct!!.item!!, it.recipe, id("${tag}_${it.recipe.name}_$it2"), it.count) }
}.flatten()

data class cords<T>(val x: T, val y: T)
data class bounds(val lx: Long, val ux: Long, val ly: Long, val uy: Long)
data class id(val string: String)
data class layoutItem(val bounds: bounds, val item: Item, val recipe: Recipe, val id: id, val amount: Double, var usage: Double = 0.0)

data class layout(val bounds: bounds, val sourceItemsLoc: List<sourceItemLoc>)
data class sourceItemLoc(val item: Item, val cords: cords<Long>, val force: Double = 1.0)

data class req(val recipes: List<recipeAndCount>, val items: List<itemAndCount>) {
    val requirementsHumanOutput: Unit
        get() {
            logs.add("")
            logs.add("")
            logs.add("total recipes: ${this.recipes.size} (Σ assembler runs: ${String.format("%.2f", this.recipes.sumOf { it.count })})")

            this.recipes.forEach { logs.add("(assemblers: ${String.format("%.2f", it.noAssemblingMachines)}x) (amount: ${String.format("%.2f", it.count)}/s) -- ${it.recipe.name}") }
            logs.add("")
            logs.add("total essential items: ${this.items.size} (Σitems = ${String.format("%.2f", this.items.sumOf { it.count })})")
            this.items.forEach { logs.add("${it.item.name}: ${String.format("%.2f", it.count)}/s") }
            logs.add("")
            logs.add("")
        }
}
data class itemAndCount(val item: Item, var count: Double)
data class recipeAndCount(val recipe: Recipe, var count: Double) {
    val noAssemblingMachines: Double
        get() = this.count * this.recipe.energy / this.recipe.mainProduct!!.amount
}
data class recipeReq(val recipe: Recipe, val count: Double)

fun requirements(recipes: List<recipeReq>, data: RootData): req = recipes.map { recipe ->
    itemRequirements(recipe.recipe, (recipe.count / (recipe.recipe.mainProduct?.amount ?: 1.0)), data) }
    .let { req(it.map {it.recipes}.flatten(), it.map { it.items }.flatten()) }
    .let {
        it.recipes.forEach { logs.add("using recipe: ${it.recipe.name}") }
        val set = it.items.distinctBy { it.item }.map { itemAndCount(it.item, 0.0) }
        it.items.forEach { set.find { s -> s.item.name == it.item.name }!!.count += it.count }
        val recipesNew: MutableList<recipeAndCount> =
            it.recipes.distinctBy { it.recipe }.map { recipeAndCount(it.recipe, 0.0) } as MutableList<recipeAndCount>
        it.recipes.forEach { recipesNew.find { r -> r.recipe == it.recipe }!!.count += it.count }
        recipes.forEach { r ->
            val existingRecipe = recipesNew.find { it.recipe == r.recipe}
            if (existingRecipe != null) existingRecipe.count += r.count
            else recipesNew.add(recipeAndCount(r.recipe, r.count))
        }
        req(recipesNew, set)
    }

fun itemRequirements(recipe: Recipe, amount: Double, data: RootData, nested: Int = 0, recipes: MutableList<recipeAndCount> = mutableListOf(), items: MutableList<itemAndCount> = mutableListOf()): req {
    recipe.ingredients.map { recipe ->
        val newRecipe = data.recipes[recipe.name]
        if (newRecipe != null) {
            recipes.add(recipeAndCount(newRecipe, recipe.amount * amount))
            itemRequirements(newRecipe, recipe.amount * amount / newRecipe.mainProduct!!.amount, data, nested + 1, recipes, items)
        } else {
            if (data.items[recipe.name] != null) items.add(itemAndCount(data.items[recipe.name]!!, recipe.amount * amount))
            else logs.add("couldn't find ${recipe.name}")
        }
    }
    return req(recipes, items)
}