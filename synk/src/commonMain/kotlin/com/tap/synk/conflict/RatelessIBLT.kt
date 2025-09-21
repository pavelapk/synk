package com.tap.synk.conflict

import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random

private enum class Direction(val sign: Long) {
    ADD(1),
    REMOVE(-1);
}

private data class HashedSymbol(
    val symbol: Long,
    val hash: Long,
)

private class CodedSymbol {
    var symbol: Long = 0L
    var hash: Long = 0L
    var count: Long = 0L

    fun apply(hashedSymbol: HashedSymbol, direction: Direction) {
        symbol = symbol xor hashedSymbol.symbol
        hash = hash xor hashedSymbol.hash
        count += direction.sign
    }

    fun toRatelessSymbol(): RatelessSymbol = RatelessSymbol(
        keySum = hash,
        valueSum = symbol,
        count = count,
    )
}

private class SymbolMapping(seed: Long) : Iterator<Int> {
    private val rng = Random(seed.hashCode())
    private var lastMappedIdx: Int = 0

    override fun hasNext(): Boolean = true

    override fun next(): Int {
        val r = rng.nextDouble()
        val i = lastMappedIdx.toDouble()
        val uSqrtInv = 1.0 / sqrt(1.0 - r)
        val diff = ceil((1.5 + i) * (uSqrtInv - 1.0)).toInt()
        val value = lastMappedIdx
        lastMappedIdx += diff
        return value
    }
}

private data class HashedSymbolMapping(
    val hashedSymbol: HashedSymbol,
    var mapping: SymbolMapping,
)

private data class SourceSymbolIdxToLastMappingIdx(
    val sourceSymbolIdx: Int,
    val lastMappingIdx: Int,
)

private sealed class DecodedSymbolIdx {
    data class Local(val index: Int) : DecodedSymbolIdx()
    data class Remote(val index: Int) : DecodedSymbolIdx()
}

private data class DecodedSymbolIdxToLastMappingIdx(
    val decodedSymbolIdx: DecodedSymbolIdx,
    val lastMappingIdx: Int,
)

private class MinHeap<T>(private val comparator: Comparator<T>) {
    private val backing = mutableListOf<T>()

    fun push(value: T) {
        backing.add(value)
        backing.sortWith(comparator)
    }

    fun pop(): T? = if (backing.isEmpty()) null else backing.removeAt(0)

    fun peek(): T? = backing.firstOrNull()

    fun isEmpty(): Boolean = backing.isEmpty()
}

internal class RatelessIBLT(private val hasher: Hasher64) {
    private val sketch: MutableList<CodedSymbol> = mutableListOf()
    private var subtractedIndex: Int = -1
    private val sourceSymbols = mutableListOf<HashedSymbolMapping>()
    private val nextMappingIdx = MinHeap(compareBy<SourceSymbolIdxToLastMappingIdx> { it.lastMappingIdx })
    private val nextPeelingIdx = MinHeap(compareBy<DecodedSymbolIdxToLastMappingIdx> { it.lastMappingIdx })
    private val localOnly = mutableListOf<HashedSymbolMapping>()
    private val remoteOnly = mutableListOf<HashedSymbolMapping>()
    private val decoded = mutableSetOf<Long>()

    fun addSymbol(symbol: Long) {
        val hashedSymbol = HashedSymbol(symbol = symbol, hash = hashSymbol(symbol))
        val mapping = SymbolMapping(hashedSymbol.hash)
        val firstMappingIdx = mapping.next()
        sourceSymbols += HashedSymbolMapping(hashedSymbol, mapping)
        nextMappingIdx.push(SourceSymbolIdxToLastMappingIdx(sourceSymbols.lastIndex, firstMappingIdx))
    }

    fun extendSketch(extraSize: Int) {
        val currentLen = sketch.size
        repeat(extraSize) {
            sketch.add(CodedSymbol())
        }
        while (true) {
            val entry = nextMappingIdx.pop() ?: break
            if (entry.lastMappingIdx >= sketch.size) {
                nextMappingIdx.push(entry)
                break
            }
            val codedSymbol = sketch[entry.lastMappingIdx]
            val original = sourceSymbols[entry.sourceSymbolIdx]
            codedSymbol.apply(original.hashedSymbol, Direction.ADD)
            val nextIdx = original.mapping.next()
            nextMappingIdx.push(SourceSymbolIdxToLastMappingIdx(entry.sourceSymbolIdx, nextIdx))
        }
    }

    private fun tryMarkPureCell(idx: Int) {
        val cell = sketch[idx]
        if (cell.count == 0L) return
        val symbolHash = hashSymbol(cell.symbol)
        if (symbolHash != cell.hash) return
        if (!decoded.add(cell.symbol)) return

        val hashedSymbol = HashedSymbol(symbol = cell.symbol, hash = cell.hash)
        val mapping = SymbolMapping(hashedSymbol.hash)
        val firstMappingIdx = mapping.next()
        when (cell.count) {
            1L -> {
                localOnly += HashedSymbolMapping(hashedSymbol, mapping)
                nextPeelingIdx.push(
                    DecodedSymbolIdxToLastMappingIdx(
                        decodedSymbolIdx = DecodedSymbolIdx.Local(localOnly.lastIndex),
                        lastMappingIdx = firstMappingIdx,
                    ),
                )
            }
            -1L -> {
                remoteOnly += HashedSymbolMapping(hashedSymbol, mapping)
                nextPeelingIdx.push(
                    DecodedSymbolIdxToLastMappingIdx(
                        decodedSymbolIdx = DecodedSymbolIdx.Remote(remoteOnly.lastIndex),
                        lastMappingIdx = firstMappingIdx,
                    ),
                )
            }
        }
    }

    private fun peel() {
        while (true) {
            val entry = nextPeelingIdx.pop() ?: break
            if (entry.lastMappingIdx >= sketch.size) {
                nextPeelingIdx.push(entry)
                break
            }
            val (decodedSymbolIdx, lastMappingIdx) = entry
            val (mappingList, direction) = when (decodedSymbolIdx) {
                is DecodedSymbolIdx.Local -> localOnly to Direction.REMOVE
                is DecodedSymbolIdx.Remote -> remoteOnly to Direction.ADD
            }
            val symbolMapping = when (decodedSymbolIdx) {
                is DecodedSymbolIdx.Local -> mappingList[decodedSymbolIdx.index]
                is DecodedSymbolIdx.Remote -> mappingList[decodedSymbolIdx.index]
            }
            val codedSymbol = sketch[lastMappingIdx]
            codedSymbol.apply(symbolMapping.hashedSymbol, direction)
            val nextIdx = symbolMapping.mapping.next()
            nextPeelingIdx.push(
                DecodedSymbolIdxToLastMappingIdx(
                    decodedSymbolIdx = decodedSymbolIdx,
                    lastMappingIdx = nextIdx,
                ),
            )
            tryMarkPureCell(lastMappingIdx)
        }
    }

    fun subtract(other: RatelessIBLT) {
        require(sketch.size == other.sketch.size) { "Sketch sizes must match" }
        val start = if (subtractedIndex >= 0) subtractedIndex + 1 else 0
        if (start >= sketch.size) return
        for (i in start until sketch.size) {
            val c1 = sketch[i]
            val c2 = other.sketch[i]
            c1.symbol = c1.symbol xor c2.symbol
            c1.hash = c1.hash xor c2.hash
            c1.count -= c2.count
            tryMarkPureCell(i)
            peel()
        }
        subtractedIndex = sketch.size - 1
    }

    fun isDecoded(): Boolean {
        if (sketch.isEmpty()) return false
        val first = sketch[0]
        return first.hash == 0L && first.symbol == 0L && first.count == 0L
    }

    fun emitNext(batchSize: Int): List<RatelessSymbol> {
        if (batchSize <= 0) return emptyList()
        val previous = sketch.size
        extendSketch(batchSize)
        val symbols = mutableListOf<RatelessSymbol>()
        for (i in previous until sketch.size) {
            symbols += sketch[i].toRatelessSymbol()
        }
        return symbols
    }

    fun appendSymbols(symbols: List<RatelessSymbol>) {
        symbols.forEach { symbol ->
            val coded = CodedSymbol().apply {
                hash = symbol.keySum
                this.symbol = symbol.valueSum
                count = symbol.count
            }
            sketch += coded
        }
    }

    fun ensureSketchSize(size: Int) {
        val required = size - sketch.size
        if (required > 0) {
            extendSketch(required)
        }
    }

    fun size(): Int = sketch.size

    fun findAllDifferences(other: RatelessIBLT): Int {
        extendSketch(1)
        other.extendSketch(1)
        while (true) {
            subtract(other)
            if (isDecoded()) {
                return sketch.size
            }
            extendSketch(1)
            other.extendSketch(1)
        }
    }

    fun getLocalOnlySymbols(): List<Long> = localOnly.map { it.hashedSymbol.symbol }

    fun getRemoteOnlySymbols(): List<Long> = remoteOnly.map { it.hashedSymbol.symbol }

    fun toPayload(namespace: String): RatelessSketchPayload =
        RatelessSketchPayload(
            namespace = namespace,
            symbols = sketch.map { it.toRatelessSymbol() },
        )

    companion object {
        fun fromSymbols(symbols: Iterable<Long>, hasher: Hasher64): RatelessIBLT {
            val riblt = RatelessIBLT(hasher)
            symbols.forEach { riblt.addSymbol(it) }
            return riblt
        }

        fun fromPayload(payload: RatelessSketchPayload, hasher: Hasher64): RatelessIBLT {
            val riblt = RatelessIBLT(hasher)
            payload.symbols.forEach { symbol ->
                val coded = CodedSymbol().apply {
                    hash = symbol.keySum
                    this.symbol = symbol.valueSum
                    count = symbol.count
                }
                riblt.sketch += coded
            }
            if (riblt.sketch.isEmpty()) {
                riblt.extendSketch(1)
            }
            riblt.subtractedIndex = -1
            return riblt
        }
    }

    private fun hashSymbol(symbol: Long): Long {
        val buffer = ByteArray(Long.SIZE_BYTES)
        for (i in 0 until Long.SIZE_BYTES) {
            buffer[i] = ((symbol ushr (i * 8)) and 0xffL).toByte()
        }
        return hasher.hash(buffer)
    }
}
