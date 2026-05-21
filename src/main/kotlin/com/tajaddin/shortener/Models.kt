package com.tajaddin.shortener

import kotlinx.serialization.Serializable

@Serializable
data class CreateLinkRequest(
    val url: String,
    val code: String? = null,
)

@Serializable
data class LinkResponse(
    val code: String,
    val target: String,
    val hits: Long,
    val createdAtEpochMs: Long,
)

@Serializable
data class PagedLinks(
    val items: List<LinkResponse>,
    val offset: Int,
    val limit: Int,
)

@Serializable
data class ErrorResponse(val error: String)

/** Thrown when a requested custom code already exists. */
class CodeTakenException(val code: String) : RuntimeException("code already in use: $code")

/** Thrown when the submitted URL is not acceptable. */
class InvalidUrlException(message: String) : RuntimeException(message)
