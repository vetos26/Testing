package kt1lin.streams.jdk8

import java.util.stream.DoubleStream
import java.util.stream.IntStream
import java.util.stream.LongStream
import java.util.stream.Stream
import kotlin.sequences.Sequence

// Заглушка вместо kotlin.streams.jdk8.StreamsKt из stdlib 1.9.25 (invokedynamic).
// Пакет kt1lin/ той же длины, что kotlin/ — байтовая замена после компиляции.
@Suppress("unused")
object StreamsKt {
    @JvmStatic
    fun <T> asSequence(stream: Stream<T>): Sequence<T> = Sequence { stream.iterator() }

    @JvmStatic
    fun asSequence(stream: IntStream): Sequence<Int> = Sequence { stream.iterator() }

    @JvmStatic
    fun asSequence(stream: LongStream): Sequence<Long> = Sequence { stream.iterator() }

    @JvmStatic
    fun asSequence(stream: DoubleStream): Sequence<Double> = Sequence { stream.iterator() }
}
