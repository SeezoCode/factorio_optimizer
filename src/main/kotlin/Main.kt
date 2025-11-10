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
val path = "src/main/resources/solves/$launchTime - solve"

fun main() {
    val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerTypeAdapter(Entity::class.java, EntityDeserializer())
        .registerTypeAdapterFactory(ListOrObjectAdapterFactory())
        .create()

    val jsonFileName = "recipes.json"
    val jsonString: String? = RootData::class.java.getResourceAsStream(jsonFileName)
        ?.bufferedReader()
        ?.use { it.readText() }

//    deleteFolderRecursively("src/main/resources/solves/$launchTime - solve/allSolves")
    try {
        Files.createDirectory(Paths.get(path))
        Files.createDirectory(Paths.get("$path/allSolves"))
    } catch (e: IOException) {}

    val data = gson.fromJson(jsonString, RootData::class.java)
    interlinkProductsWithItems(data)

    println("\n--- Successfully Parsed JSON Data with Gson ---")
    println("Game Version: ${data.gameVersion}")
    println("Found ${data.recipes.size} recipes.")
    println("Found ${data.items.size} items.")
    println("Found ${data.fluids.size} fluids.")
    println("Found ${data.entities.size} entities.")

    val idk = (data.recipes.map { it.value.category }).toSet()
//    println(idk)

    val scalingFactor = 1000L

//    println(data.recipes["electronic-circuit"])

    val appropriate = listOf("crafting", "pressing", "intermediate-products", "electronics", "crafting-with-fluid", "advanced-crafting", "electronics-with-fluid")

    data.recipes = data.recipes.filter { entry ->
        entry.value.category in appropriate
    }

    data.recipes = data.recipes.filter { it.value.category in appropriate }
    println("Found ${data.recipes.size} recipes.")

    println("\n")
//    println(data.recipes["electric-engine-unit"])
//        itemRequirements(data.recipes["transport-belt"]!!, 1.0, data)
    println()
    val x = requirements(listOf(
        recipeReq(data.recipes["utility-science-pack"]!!, 1.0),
//        recipeReq(data.recipes["electric-engine-unit"]!!, 4.0)
    ), data)
    (x).requirementsHumanOutput

    Loader.loadNativeLibraries()
    val model = CpModel()

    val ms = x.recipes.fold(0) { acc, item -> acc + ceil(item.noAssemblingMachines).toInt() }
    val maxX = ceil(ms.toDouble().pow(.5)).toLong() +1
    val maxY = ceil(ms.toDouble().pow(.5)).toLong() +1

    val sourceOffset = 25
    val layoutOfSources = listOf<sourceItemLoc>(
        sourceItemLoc(data.items["iron-plate"]!!, cords(0,0)),
//            sourceItemLoc(data.items["iron-plate"]!!, cords(2,0)),
        sourceItemLoc(data.items["copper-plate"]!!, cords(5,0)),
//            sourceItemLoc(data.items["advanced-circuit"]!!, cords(20,0))
//        sourceItemLoc(data.items["utility-science-pack"]!!, cords(5,5), 1000.0)

        )

    layoutOfSources.forEach { logs.add("layoutOfSources: ${it.item.name}, (${it.cords.x}, ${it.cords.y}, force: ${it.force}") }

//    println(layout)
//    data.recipes["fast-inserter"]!!.products.forEach { println(it.item) }
//    println(data.recipes["fast-inserter"]!!.mainProduct!!.item)
//    x.recipes.forEach { model.newIntVar(0, maxX.toLong(), "x_") }
    val bound1 = bounds(1, maxX, 1, maxX)
    println(bound1)
    logs.add(bound1.toString())
    val layoutOfRecipes = x.getLayoutItems(bound1, "x")
//    println()
//    println(layoutOfRecipes)

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
    } } }

    val sourcesIntVars = layoutOfSources.map {

    }

    val distances = cordsIntVars.map { consumer ->
        consumer.layoutItem.recipe.ingredients.map { ingredient ->
//            Pair(data.recipes[it.name], it.amount)
            val totalIngredientNeed = ingredient.amount * consumer.layoutItem.amount
            var ingredientNeedLeft = totalIngredientNeed
            val feasibleLayoutOptions = cordsIntVars.filter { f -> f.layoutItem.item == ingredient.item }



            val distancesToSources = (layoutOfSources.filter { it.item == ingredient.item || it.item == consumer.layoutItem.item }).groupBy { it.item }.let {
                it.keys.map { key ->
                    val sameSources = it[key]!!.map { source ->
                        val distXY = model.newIntVar(
                            0, (maxX + maxY + sourceOffset * 2), // will be incorrect
                            "dist_src_${consumer.layoutItem.id.string}_${source.item.name}_${source.cords.x}_${source.cords.y}"
                        )
                        val distX =
                            model.newIntVar(0, maxX + sourceOffset, "dist_x_src_${consumer.layoutItem.id.string}_${source.item.name}")
                        val distY =
                            model.newIntVar(0, maxY + sourceOffset, "dist_y_src_${consumer.layoutItem.id.string}_${source.item.name}")

                        // |consumer.x - source.x_CONSTANT|
                        model.addAbsEquality(
                            distX,
                            LinearExpr.sum(arrayOf(consumer.cords.x, LinearExpr.constant(-source.cords.x)))
                        )
                        // |consumer.y - source.y_CONSTANT|
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
//                val weight = model.newIntVar(0, (assemblerIngredientProduction * scalingFactor).toLong() + totalIngredientNeed.toLong() + 1, "weight_${consumer.layoutItem.id.string}_${ingredientAss.layoutItem.id.string}") // something extra for ub, because of rounding

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
//            model.addAbsEquality(LinearExpr.constant((totalIngredientNeed * scalingFactor).toLong()), distanceToProducers.map { it.second }.toTypedArray())
//            model.addEquality(LinearExpr.constant((totalIngredientNeed * scalingFactor).toLong()), LinearExpr.sum(distanceToProducers.map { it.second }.toTypedArray()))
//            val allPossibleDistances = distanceToProducers.map { it.first } + distancesToSources
//            allPossibleDistances
            distanceToProducers + distancesToSources
        }
    }.flatten().flatten()

    val totalDistance = model.newIntVar(0, Int.MAX_VALUE.toLong(), "total_distance")
    model.addEquality(totalDistance, LinearExpr.sum((distances).toTypedArray()))

    model.minimize(totalDistance)

    val solver = CpSolver()
    val callback = MySolutionPrinter(bound1, cordsIntVars)
    val status = solver.solve(model, callback)

    if (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE) {
        println("\n\n\nAnd finally:\n\n")
        cordsIntVars.printHumanOutput(bound1, solver)
    } else {
        println("No solution found.");
    }


}

fun List<layoutItemAndIntVar>.printHumanOutput(bounds: bounds, solver: CpSolver) {
    println("-------------------- LAYOUT --------------------")
    val emptySlot = " ".repeat(20) // Empty string of the same length
    (bounds.ly..bounds.uy).forEach { y ->
        val rowItems = (bounds.lx..bounds.ux).map { x ->
            // Find the assembler at this exact (x, y) coordinate
            val assembler = this.find {
                solver.value(it.cords.x) == x && solver.value(it.cords.y) == y
            }

            assembler?.layoutItem?.item?.name?.formatToLength(20) ?: emptySlot
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
            logs.add("total recipes: ${this.recipes.size} (Σ assembler runs: ${this.recipes.sumOf { it.count }})")

            this.recipes.forEach { logs.add("(assemblers: ${String.format("%.2f", it.noAssemblingMachines)}x) (amount: ${String.format("%.2f", it.count)}/s) -- ${it.recipe.name}") }
            logs.add("\ntotal items: ${this.items.size} (Σitems = ${this.items.sumOf { it.count }})")
            this.items.forEach { logs.add("${it.item.name}: ${String.format("%.2f", it.count)}/s") }
            logs.add("\n")
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
        it.recipes.forEach { logs.add(it.recipe.name) }
        val set = it.items.distinctBy { it.item }.map { itemAndCount(it.item, 0.0) }
        it.items.forEach { set.find { s -> s.item.name == it.item.name }!!.count += it.count }
        val recipesNew: MutableList<recipeAndCount> =
            it.recipes.distinctBy { it.recipe }.map { recipeAndCount(it.recipe, 0.0) } as MutableList<recipeAndCount>
        it.recipes.forEach { recipesNew.find { r -> r.recipe == it.recipe }!!.count += it.count }
        recipes.forEach { r ->
            val existingRecipe = recipesNew.find { it.recipe == r.recipe}
            if (existingRecipe != null) existingRecipe.count += r.count
            else recipesNew.add(recipeAndCount(r.recipe, r.count)) // / r.recipe.mainProduct!!.amount))
        }
        req(recipesNew, set)
    }



fun itemRequirements(recipe: Recipe, amount: Double, data: RootData, nested: Int = 0, recipes: MutableList<recipeAndCount> = mutableListOf(), items: MutableList<itemAndCount> = mutableListOf()): req {
//    println("${"    ".repeat(nested)}|___ ${amount}x ${recipe.name}")
    recipe.ingredients.map { recipe ->
//        println("${"    ".repeat(nested)}searching for ${recipe.name}")
        val newRecipe = data.recipes[recipe.name]
        if (newRecipe != null) {
            recipes.add(recipeAndCount(newRecipe, recipe.amount * amount))
            itemRequirements(newRecipe, recipe.amount * amount / newRecipe.mainProduct!!.amount, data, nested + 1, recipes, items)
        } else {
            if (data.items[recipe.name] != null) items.add(itemAndCount(data.items[recipe.name]!!, recipe.amount * amount))
            else logs.add("couldn't find ${recipe.name}")
//            println("${"    ".repeat(nested + 1)}    - ${recipe.amount * amount}x ${recipe.name}")
        }
    }
    return req(recipes, items)
}

