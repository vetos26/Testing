package kt1lin.comparisons

import java.util.Comparator
import kotlin.jvm.functions.Function1

// Замена лямбда-класса stdlib 1.9.25 compareByDescending(selector) —
// см. LambdaCompareBy.kt: имя и конструктор должны совпадать со stdlib.
@Suppress("unused", "ClassName")
class `ComparisonsKt__ComparisonsKt$compareByDescending$1`(
    private val selector: Function1<Any?, Comparable<*>?>
) : Comparator<Any?> {

    override fun compare(a: Any?, b: Any?): Int {
        val va = if (a == null) null else selector.invoke(a)
        val vb = if (b == null) null else selector.invoke(b)
        return when {
            va === vb -> 0
            vb == null -> -1
            va == null -> 1
            else -> (vb as Comparable<Any?>).compareTo(va)
        }
    }
}
