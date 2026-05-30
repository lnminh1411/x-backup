import org.junit.Test
import java.lang.reflect.Modifier

class HashBenchmark {
    @Test
    fun benchmark() {
        println("=== Probing Level / ServerLevel ===")
        try {
            val levelClass = Class.forName("net.minecraft.world.level.Level", false, HashBenchmark::class.java.classLoader)
            println("Class net.minecraft.world.level.Level found.")
            for (method in levelClass.methods) {
                val name = method.name.lowercase()
                if (name.contains("time") || name.contains("game")) {
                    val modifiers = Modifier.toString(method.modifiers)
                    val params = method.parameterTypes.joinToString { it.name }
                    println("$modifiers ${method.returnType.name} ${method.name}($params)")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
