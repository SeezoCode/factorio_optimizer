import com.google.gson.Gson
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.File
import java.io.IOException
import java.lang.reflect.Type
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class RootData(
    @SerializedName("game_version") val gameVersion: String,
    val groups: Map<String, Group>,
    val quality: Map<String, Quality>,
    @SerializedName("quality_names") val qualityNames: List<String>,
    var recipes: Map<String, Recipe>,
    val items: Map<String, Item>,
    val fluids: Map<String, Fluid>,
    val entities: Map<String, Entity>
)

data class Group(
    val name: String,
    val type: String,
    val order: String,
    val orderInRecipe: String
)

data class Quality(
    val name: String,
    val level: Int,
    val nextProbability: Double,
    val beaconPowerUsageMultiplier: Double,
    val miningDrillResourceDrainMultiplier: Double,
    val group: String,
    val subgroup: String,
    val next: String? = null,
    val translatedName: String
)

data class Recipe(
    val name: String,
    val category: String,
    val ingredients: List<Ingredient> = emptyList(),
    val products: List<Product> = emptyList(),
    val mainProduct: Product? = null,
    val allowedEffects: AllowedEffects,
    val maximumProductivity: Int,
    val energy: Double,
    val order: String,
    val group: String,
    val subgroup: String,
    val enabled: Boolean,
    val productivityBonus: Int,
    val translatedName: String
)

data class Ingredient(
    val type: String,
    var item: Item? = null,
    val name: String,
    val amount: Double,
    val fluidboxMultiplier: Int? = null,
    val ignoredByStats: Double? = null,
    val fluidboxIndex: Int? = null
)

data class Product(
    val type: String,
    var item: Item? = null,
    val name: String,
    val amount: Double,
    val probability: Double? = null,
    val temperature: Double? = null,
    val extraCountFraction: Double? = null,
    val fluidboxIndex: Int? = null,
    val fluidboxMultiplier: Int? = null,
    val percentSpoiled: Double? = null,
    val ignoredByStats: Double? = null,
    val ignoredByProductivity: Double? = null
)

data class AllowedEffects(
    val consumption: Boolean,
    val speed: Boolean,
    val productivity: Boolean,
    val pollution: Boolean,
    val quality: Boolean
)

data class Item(
    val name: String,
    val type: String,
    val order: String,
    val group: String,
    val subgroup: String,
    val stackSize: Int,
    val weight: Double,
    val fuelValue: Long,
    val rocketLaunchProducts: JsonObject,
    val translatedName: String,
    val fuelCategory: String? = null,
    val flags: List<String>? = null,
    val moduleEffects: ModuleEffects? = null
)

data class ModuleEffects(
    val consumption: Double? = null,
    val speed: Double? = null,
    val productivity: Double? = null,
    val pollution: Double? = null,
    val quality: Double? = null
)

data class Fluid(
    val name: String,
    val order: String,
    val group: String,
    val subgroup: String,
    val fuelValue: Long,
    val translatedName: String
)

interface Entity {
    val name: String; val type: String; val order: String; val group: String
    val subgroup: String; val moduleInventorySize: Int; val width: Int
    val height: Int; val flags: List<String>; val translatedName: String
}
data class BoilerEntity(override val name: String, override val type: String, override val order: String, override val group: String, override val subgroup: String, override val moduleInventorySize: Int, override val width: Int, override val height: Int, override val flags: List<String>, override val translatedName: String) : Entity
data class EffectReceiver(val baseEffect: Map<String, Double>, val usesModuleEffects: Boolean, val usesBeaconEffects: Boolean, val usesSurfaceEffects: Boolean)
data class FurnaceEntity(override val name: String, override val type: String, override val order: String, override val group: String, override val subgroup: String, val craftingSpeed: Map<String, Double>, val craftingCategories: List<String>, val allowedEffects: List<String>, override val moduleInventorySize: Int, val effectReceiver: EffectReceiver, val energyConsumption: Double, val drain: Double, val energySource: String, val fuelCategories: List<String>? = null, override val width: Int, override val height: Int, override val flags: List<String>, override val translatedName: String) : Entity
data class AssemblingMachineEntity(override val name: String, override val type: String, override val order: String, override val group: String, override val subgroup: String, val craftingSpeed: Map<String, Double>, val craftingCategories: List<String>, val allowedEffects: List<String>, override val moduleInventorySize: Int, val effectReceiver: EffectReceiver, val energyConsumption: Double, val drain: Double, val energySource: String, val fuelCategories: List<String>? = null, val fixedRecipe: String? = null, override val width: Int, override val height: Int, override val flags: List<String>, override val translatedName: String) : Entity
data class BeaconEntity(override val name: String, override val type: String, override val order: String, override val group: String, override val subgroup: String, val allowedEffects: List<String>, override val moduleInventorySize: Int, val distributionEffectivity: Double, val distributionEffectivityBonusPerQualityLevel: Double, val supplyAreaDistance: Map<String, Int>, val energyConsumption: Double, val drain: Double, val energySource: String, override val width: Int, override val height: Int, override val flags: List<String>, override val translatedName: String) : Entity
data class RocketSiloEntity(override val name: String, override val type: String, override val order: String, override val group: String, override val subgroup: String, val craftingSpeed: Map<String, Double>, val craftingCategories: List<String>, val allowedEffects: List<String>, override val moduleInventorySize: Int, val effectReceiver: EffectReceiver, val rocketPartsRequired: Int, val energyConsumption: Double, val drain: Double, val energySource: String, val fixedRecipe: String? = null, override val width: Int, override val height: Int, override val flags: List<String>, override val translatedName: String) : Entity


class EntityDeserializer : JsonDeserializer<Entity> {
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): Entity {
        val jsonObject = json.asJsonObject
        val type = jsonObject.get("type").asString
        val clazz = when (type) {
            "boiler" -> BoilerEntity::class.java
            "furnace" -> FurnaceEntity::class.java
            "assembling-machine" -> AssemblingMachineEntity::class.java
            "beacon" -> BeaconEntity::class.java
            "rocket-silo" -> RocketSiloEntity::class.java
            else -> throw JsonParseException("Unknown entity type: $type")
        }
        return context.deserialize(jsonObject, clazz)
    }
}

// --- NEW AND IMPROVED: A TypeAdapterFactory to handle the {} vs [] problem ---
class ListOrObjectAdapterFactory : TypeAdapterFactory {
    override fun <T> create(gson: Gson, typeToken: TypeToken<T>): TypeAdapter<T>? {
        // We are only interested in handling List types
        if (!List::class.java.isAssignableFrom(typeToken.rawType)) {
            return null // Let Gson's default factories handle it
        }

        // Get the default adapter for this list type from Gson.
        // This is the crucial part: we delegate the actual array parsing.
        val delegateAdapter = gson.getDelegateAdapter(this, typeToken)

        // Return our custom adapter that intercepts the JSON
        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T?) {
                // We only care about reading, so just delegate writing
                delegateAdapter.write(out, value)
            }

            override fun read(input: JsonReader): T? {
                // Check the next token without consuming it
                if (input.peek() == JsonToken.BEGIN_OBJECT) {
                    input.skipValue() // Safely consume and discard the empty {} object
                    // Since we know T is a List, we can cast an empty list to it
                    @Suppress("UNCHECKED_CAST")
                    return emptyList<Any?>() as T
                }
                // If it's not an object, it must be an array or null.
                // Let the default delegate adapter handle it.
                return delegateAdapter.read(input)
            }
        }.nullSafe()
    }
}

fun interlinkProductsWithItems(data: RootData) = data.recipes.forEach {
    it.value.products.forEach { it.item = data.items[it.name] }
    it.value.mainProduct?.item = data.items[it.value.mainProduct!!.name]
    it.value.ingredients.forEach { it.item = data.items[it.name] }
//    it.value.ingredients.forEach { it }
}

fun String.formatToLength(length: Int, padChar: Char = ' '): String {
    return this.padEnd(length, padChar).take(length)
}


data class QualityFilter(
    val index: Int,
    val quality: String,  // "normal", "good", "excellent", etc.
    val comparator: String  // "=", ">", "<"
)

// The root object
data class BlueprintRoot(
    val blueprint: Blueprint
)

data class Blueprint(
    val entities: List<BlueprintEntity> = emptyList(),
    val icons: List<Icon> = emptyList(),
    val version: Long = 281479276658688, // This is just a standard version number
    val label: String = "My AI Layout"
)

data class BlueprintEntity(
    val entity_number: Int,
    val name: String, // e.g., "assembling-machine-2"
    val position: Position,
    val recipe: String? = null, // e.g., "electronic-circuit"
    val direction: Int = 2, // 2 = South (looks nice)
    val request_from_buffers: Boolean? = null,
//    val request_filters: List<RequestFilter>? = null,
    val items: Map<String, Int>? = null,
    val neighbours: List<Int>? = null,
    val bar: Int? = null,
    val filter_mode: String? = null,
    val use_filters: Boolean? = null,
    val filters: List<QualityFilter>? = null,
    val request_filters: Any? = null,
)

data class RequestFilter(
    val index: Int, // 1-based index
    val name: String,
    val count: Int
)

data class Position(
    val x: Double,
    val y: Double
)

data class Icon(
    val signal: Signal,
    val index: Int
)

data class Signal(
    val type: String, // "item"
    val name: String  // "utility-science-pack"
)


data class MyDataRoot(
    val sections: List<Section>,
    val index: Int
)

/**
 * Represents an object within the "sections" array.
 */
data class Section(
    val index: Int,
    val filters: List<Filter>
)

/**
 * Represents an object within the "filters" array.
 */
data class Filter(
    val index: Int,
    val name: String,
    val quality: String,
    val comparator: String,
    val count: Int
)


data class StorageItemFilter(
    val index: Int,
    val name: String,
    val quality: String,
    val comparator: String,
    val count: Int = 1 // Ignored by storage chest, but good to include
)

data class StorageChestRequest(
    val sections: List<StorageChestSection>,
    val index: Int
)

/**
 * Represents one object in the "sections" array.
 */
data class StorageChestSection(
    val index: Int,
    val filters: List<StorageChestFilter>
)

/**
 * Represents one object in the "filters" array.
 */
data class StorageChestFilter(
    val index: Int,
    val name: String,
    val quality: String,
    val comparator: String,
    val count: Int
)


fun deleteFolderRecursively(folderPath: String): Boolean {
    val folder = File(folderPath)

    if (!folder.exists()) {
        println("Folder '$folderPath' already absent. (Success)")
        return true
    }

    if (!folder.isDirectory) {
        println("Error: '$folderPath' is a file, not a directory.")
        return false
    }

    // Attempts to delete the directory and all of its contents.
    val wasDeleted = folder.deleteRecursively()

    if (wasDeleted) {
        println("Successfully removed the folder and its contents: $folderPath")
    } else {
        println("Failed to remove the folder: $folderPath (Check permissions/file locks)")
    }

    return wasDeleted
}

fun printFormattedTime(): String {
    // 1. Define the desired format string.
    // 'yyyy' = year, 'MM' = month (01-12), 'dd' = day of month
    // 'HH' = hour (00-23), 'mm' = minute, 'ss' = second
    val formatPattern = "yyyy-MM-dd: HH:mm:ss"

    // 2. Create a formatter object using the pattern.
    val formatter = DateTimeFormatter.ofPattern(formatPattern)

    // 3. Get the current date and time from the system clock.
    val currentTime = LocalDateTime.now()

    // 4. Apply the formatter to the current time and return the result.
    return currentTime.format(formatter)
}

val launchTime = printFormattedTime()


class Log(private val filename: String = "log.txt") {
    val filenameTimed = "$launchTime - $filename"

    fun add(message: String) {
        // 1. Print the string to the console
        println("> $message")

        // 2. Save (append) the string to the file
        try {
            // File.appendText ensures the file is created if it doesn't exist
            // and appends to it. We add a newline to match the console's println.
            File("$path/$filenameTimed").appendText("$message\n")

        } catch (e: IOException) {
            // It's good practice to handle potential I/O errors (e.g., file permissions)
            System.err.println("Failed to write to log file logs/$filenameTimed: ${e.message}")
        }
    }
}

