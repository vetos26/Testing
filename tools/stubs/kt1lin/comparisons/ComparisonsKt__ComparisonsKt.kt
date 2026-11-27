package kt1lin.comparisons

import java.util.Comparator
import kotlin.jvm.functions.Function1
import kotlin.jvm.functions.Function2

// Замена kotlin.comparisons.ComparisonsKt__ComparisonsKt из stdlib 1.9.25,
// чей байткод содержит invokedynamic (недопустимо для dx/minSdk 24).
// КРИТИЧНО: настоящий stdlib-класс ComparisonsKt___ComparisonsJvmKt
// НАСЛЕДУЕТ от этого класса (цепочка facade -> ___Kt -> ___JvmKt -> __Kt),
// поэтому он ОБЯЗАН быть open, не final — иначе VerifyError на устройстве.
// Пакет длиной в 7 символов как "kotlin/" — затем байтовая замена kt1lin/ -> kotlin/.
@Suppress("unused", "EXTENSION_SHADOWED_BY_MEMBER", "NOTHING_TO_INLINE")
open class ComparisonsKt__ComparisonsKt private constructor() {

    companion object {

        @JvmStatic
        fun cmp(a: Any?, b: Any?): Int = when {
            a == null -> if (b == null) 0 else -1
            b == null -> 1
            else -> (a as Comparable<Any?>).compareTo(b)
        }

        @JvmStatic
        fun <T> compareValues(a: T?, b: T?): Int = cmp(a, b)

        @JvmStatic
        fun <T> compareBy(selector: Function1<T, Comparable<*>?>): Comparator<T> =
            Comparator { a, b -> cmp(selector.invoke(a), selector.invoke(b)) }

        @JvmStatic
        fun <T> compareBy(comparator: Comparator<in T>, selector: Function1<T, Comparable<*>?>): Comparator<T> =
            Comparator { a, b ->
                val r = comparator.compare(a, b)
                if (r != 0) r else cmp(selector.invoke(a), selector.invoke(b))
            }

        @JvmStatic
        fun <T> compareByDescending(selector: Function1<T, Comparable<*>?>): Comparator<T> =
            Comparator { a, b -> cmp(selector.invoke(b), selector.invoke(a)) }

        @JvmStatic
        fun <T> compareByDescending(comparator: Comparator<in T>, selector: Function1<T, Comparable<*>?>): Comparator<T> =
            Comparator { a, b ->
                val r = comparator.compare(a, b)
                if (r != 0) r else cmp(selector.invoke(b), selector.invoke(a))
            }

        @JvmStatic
        fun <T> thenComparator(comparator: Comparator<in T>, comparison: Function2<T, T, Int>): Comparator<T> =
            Comparator { a, b ->
                val r = comparator.compare(a, b)
                if (r != 0) r else comparison.invoke(a, b)
            }

        @JvmStatic
        fun <T> thenBy(comparator: Comparator<in T>, selector: Function1<T, Comparable<*>?>): Comparator<T> =
            Comparator { a, b ->
                val r = comparator.compare(a, b)
                if (r != 0) r else cmp(selector.invoke(a), selector.invoke(b))
            }

        @JvmStatic
        fun <T> thenBy(comparator: Comparator<in T>, other: Comparator<in T>, selector: Function1<T, Comparable<*>?>): Comparator<T> =
            Comparator { a, b ->
                val r = comparator.compare(a, b)
                if (r != 0) r else other.compare(a, b).let { if (it != 0) it else cmp(selector.invoke(a), selector.invoke(b)) }
            }

        @JvmStatic
        fun <T> thenByDescending(comparator: Comparator<in T>, selector: Function1<T, Comparable<*>?>): Comparator<T> =
            Comparator { a, b ->
                val r = comparator.compare(a, b)
                if (r != 0) r else cmp(selector.invoke(b), selector.invoke(a))
            }

        @JvmStatic
        fun <T> thenByDescending(comparator: Comparator<in T>, other: Comparator<in T>, selector: Function1<T, Comparable<*>?>): Comparator<T> =
            Comparator { a, b ->
                val r = comparator.compare(a, b)
                if (r != 0) r else other.compare(a, b).let { if (it != 0) it else cmp(selector.invoke(b), selector.invoke(a)) }
            }
    }
}
