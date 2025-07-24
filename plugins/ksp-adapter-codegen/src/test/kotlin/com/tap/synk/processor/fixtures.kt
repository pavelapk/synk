package com.tap.synk.processor

import com.tschuchort.compiletesting.SourceFile

internal val FOO_DATA_CLASS = SourceFile.kotlin(
    "Foo.kt",
    """
        package com.test.processor.db

        data class Foo(
            val bar: String,
            val baz: Int?,
            val bim: Boolean,
        )
    """,
)

internal val FOO_NOT_IMPLEMENTED_ID_RESOLVER = SourceFile.kotlin(
    "FooResolver.kt",
    """
        package com.test.processor

        import com.tap.synk.annotation.SynkAdapter
        import com.tap.synk.resolver.IDResolver

        @SynkAdapter
        class FooResolver
    """,
)

internal val FOO_ID_RESOLVER = SourceFile.kotlin(
    "FooResolver.kt",
    """
        package com.test.processor

        import com.tap.synk.annotation.SynkAdapter
        import com.tap.synk.resolver.IDResolver
        import com.test.processor.db.Foo

        @SynkAdapter
        class FooResolver : IDResolver<Foo> {
            override fun resolveId(crdt: Foo): String {
                return crdt.bar
            }
        }
    """,
)

internal val FOO_SEALED_CLASS = SourceFile.kotlin(
    "Foo.kt",
    """
        package com.test.processor

        sealed interface Foo {
            data class Bar(
                val bar: String,
                val baz: Int,
                val bim: Boolean,
            ): Foo

            data class Baz(
                val bing: String,
                val bam: String,
            ): Foo
        }
    """,
)

internal val FOO_SEALED_ID_RESOLVER = SourceFile.kotlin(
    "FooResolver.kt",
    """
        package com.test.processor

        import com.tap.synk.annotation.SynkAdapter
        import com.tap.synk.resolver.IDResolver

        @SynkAdapter
        class FooResolver : IDResolver<Foo> {
            override fun resolveId(crdt: Foo): String {
                val id = when(crdt) {
                    is Foo.Bar -> crdt.bar
                    is Foo.Baz -> crdt.bam
                }
                return id
            }
        }
    """,
)

internal val FOO_COLLECTION_CLASS = SourceFile.kotlin(
    "Foo.kt",
    """
        package com.test.processor
        data class Foo(
            val bar: List<String>,
            val baz: String,
            val bim: Set<Boolean>,
        )
    """,
)

internal val FOO_COLLECTION_ID_RESOLVER = SourceFile.kotlin(
    "FooResolver.kt",
    """
        package com.test.processor

        import com.tap.synk.annotation.SynkAdapter
        import com.tap.synk.resolver.IDResolver

        @SynkAdapter
        class FooResolver : IDResolver<Foo> {
            override fun resolveId(crdt: Foo): String {
                return crdt.baz
            }
        }
    """,
)

internal val FOO_DATA_SUB_CLASS = SourceFile.kotlin(
    "Foo.kt",
    """
        package com.test.processor
        data class Foo(
            val bar: Bar,
            val baz: String,
        )
    """,
)

internal val FOO_BAR_SUB_CLASS = SourceFile.kotlin(
    "Bar.kt",
    """
        package com.test.processor
        data class Bar(
            val bim: Bim?,
            val second: String,
        )
    """,
)

internal val FOO_BIM_SUB_CLASS = SourceFile.kotlin(
    "Bim.kt",
    """
        package com.test.processor
        data class Bim(
            val first: String,
            val second: String,
        )
    """,
)

internal val FOO_BAR_SUB_CLASS_RESOLVER = SourceFile.kotlin(
    "FooResolver.kt",
    """
        package com.test.processor

        import com.tap.synk.annotation.SynkAdapter
        import com.tap.synk.resolver.IDResolver

        @SynkAdapter
        class FooResolver : IDResolver<Foo> {
            override fun resolveId(crdt: Foo): String {
                return crdt.baz
            }
        }
    """,
)

internal val FOO_COLLECTION_DATA_CLASS = SourceFile.kotlin(
    "Foo.kt",
    """
        package com.test.processor
        data class Foo(
            val bar: List<Bar>,
        )
    """,
)

internal val BAR_COLLECTION_DATA_CLASS = SourceFile.kotlin(
    "Bar.kt",
    """
        package com.test.processor
        data class Bar(
            val bim: String,
        )
    """,
)

internal val FOO_COLLECTION_DATA_CLASS_RESOLVER = SourceFile.kotlin(
    "FooResolver.kt",
    """
        package com.test.processor

        import com.tap.synk.annotation.SynkAdapter
        import com.tap.synk.resolver.IDResolver

        @SynkAdapter
        class FooResolver : IDResolver<Foo> {
            override fun resolveId(crdt: Foo): String {
                return crdt.bar.toString()
            }
        }
    """,
)

internal val BAR_VALUE_CLASS = SourceFile.kotlin(
    "Bar.kt",
    """
        package com.test.processor
        @JvmInline
        value class Bar(val test: Int)
    """,
)

internal val BAR_VALUE_CLASS_GENERIC = SourceFile.kotlin(
    "Bar.kt",
    """
        package com.test.processor
        @JvmInline
        value class Bar<T>(val test: Int)
    """,
)

internal val BAR_VALUE_CLASS_SERIALIZER = SourceFile.kotlin(
    "BarSerializer.kt",
    """
        package com.test.processor

        import com.tap.synk.annotation.SynkSerializer
        import com.tap.synk.serialize.StringSerializer

        @SynkSerializer
        object BarSerializer: StringSerializer<Bar> {
            override fun serialize(serializable: Bar): String {
                return serializable.test.toString()
            }

            override fun deserialize(serialized: String): Bar {
                return Bar(serialized.toInt())
            }
        }
    """,
)

internal val BAR_VALUE_CLASS_GENERIC_SERIALIZER = SourceFile.kotlin(
    "BarSerializer.kt",
    """
        package com.test.processor

        import com.tap.synk.annotation.SynkSerializer
        import com.tap.synk.serialize.StringSerializer

        @SynkSerializer
        class BarSerializer<T> : StringSerializer<Bar<T>> {
            override fun serialize(serializable: Bar<T>): String {
                return serializable.test.toString()
            }

            override fun deserialize(serialized: String): Bar<T> {
                return Bar(serialized.toInt())
            }
        }
    """,
)

internal val BAZ_ENUM_CLASS = SourceFile.kotlin(
    "Baz.kt",
    """
        package com.test.processor
        enum class Baz {
            BIM,
            BAM,
        }
    """,
)

internal val FOO_VALUE_CLASS = SourceFile.kotlin(
    "Foo.kt",
    """
        package com.test.processor
        data class Foo(
            val bar: Bar,
            val barNull: Bar?,
            val baz: Baz,
        )
    """,
)

internal val FOO_VALUE_CLASS_GENERIC = SourceFile.kotlin(
    "Foo.kt",
    """
        package com.test.processor
        data class Foo(
            val bar: Bar<String>,
            val barNull: Bar<String>?,
            val baz: Baz,
        )
    """,
)

internal val FOO_RESOLVER = SourceFile.kotlin(
    "FooResolver.kt",
    """
        package com.test.processor

        import com.tap.synk.annotation.SynkAdapter
        import com.tap.synk.resolver.IDResolver

        @SynkAdapter
        class FooResolver : IDResolver<Foo> {
            override fun resolveId(crdt: Foo): String {
                return crdt.toString()
            }
        }
    """,
)
