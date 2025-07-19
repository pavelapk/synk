package com.tap.synk.fake

import com.tap.synk.CRDT
import io.github.serpro69.kfaker.Faker
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

val faker = Faker()

@OptIn(ExperimentalUuidApi::class)
internal fun crdt(id: String = Uuid.random().toString()): CRDT = CRDT(
    id,
    faker.name.firstName(),
    faker.name.lastName(),
    faker.random.nextInt(),
)
