package kt1lin.comparisons

import java.util.Comparator
import kotlin.jvm.functions.Function1

// Точные замены лямбда-классов stdlib 1.9.25, которые конструирует
// развёрнутый inline-код (ArraysKt___ArraysKt / CollectionsKt___CollectionsKt /
// SequencesKt___SequencesKt из sortBy/sortedBy/sortedByDescending):
//   ComparisonsKt__ComparisonsKt$compareBy$2(Function1) — compareBy(selector)
//   ComparisonsKt__ComparisonsKt$compareByDescending$1(Function1) — compareByDescending(selector)
// Имена и дескрипторы конструкторов должны совпадать байт-в-байт.
// Пакет kt1lin/ той же длины, что kotlin/ — замена байтов после компиляции.
@Suppress("unused", "ClassName")
class `ComparisonsKt__ComparisonsKt$compareBy$2`(
    private val selector: Function1<Any?, Comparable<*>?>
) : Comparator<Any?> {

    override fun compare(a: Any?, b: Any?): Int {
        // семантика kotlin.comparisons.compareValues: null меньше любого значения
        val va = if (a == null) null else selector.invoke(a)
        val vb = if (b == null) null else selector.invoke(b)
        return when {
            va === vb -> 0
            va == null -> -1
            vb == null -> 1
            else -> (va as Comparable<Any?>).compareTo(vb)
        }
    }
}
