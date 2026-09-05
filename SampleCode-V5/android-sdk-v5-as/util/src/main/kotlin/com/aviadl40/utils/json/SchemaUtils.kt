package com.aviadl40.utils.json

import kotlinx.schema.json.ArrayContainer
import kotlinx.schema.json.ArrayPropertyDefinition
import kotlinx.schema.json.BooleanPropertyDefinition
import kotlinx.schema.json.CommonSchemaAttributes
import kotlinx.schema.json.JsonSchema
import kotlinx.schema.json.NumericPropertyDefinition
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertiesContainer
import kotlinx.schema.json.PropertyDefinition
import kotlinx.schema.json.ReferencePropertyDefinition
import kotlinx.schema.json.StringPropertyDefinition
import kotlinx.schema.json.ValuePropertyDefinition
import kotlinx.serialization.SerializationException

fun dereference(
    definition: PropertyDefinition,
    defs: Map<String, PropertyDefinition>
): PropertyDefinition =
    (definition as? ReferencePropertyDefinition)?.let {
        definition.ref?.let {
            defs[it.substringAfterLast("/")]
                ?: throw SerializationException("Missing definition for Reference Property: $definition")
        } ?: throw SerializationException("Missing ref field in Reference Property: $definition")
    } ?: definition

fun StringBuilder.appendPropertyMarkdown(
    definition: PropertyDefinition,
    defs: Map<String, PropertyDefinition>,
    name: String? = null,
    required: Boolean = true,
    depth: Int = 0,
) {
    val indent = "\t".repeat(depth)
    val p = dereference(definition, defs)

    append(indent)
    if (depth > 0) append("- ")

    if (definition is StringPropertyDefinition && name == "type") {
        appendLine("type: ${definition.constValue}")
        return
    }

    append("${name}:")
    val (types, desc) = when (p) {
        /*is ObjectPropertyDefinition ->
            ((p.properties?.get("type") as? StringPropertyDefinition)
                ?.constValue?.toString()?.let {
                    listOf(it)
                } ?: emptyList()) + p.type to p.description*/
        is ValuePropertyDefinition<*> -> (p.type ?: emptyList()) to p.description
        is JsonSchema -> p.type to p.description
        else -> throw SerializationException("Invalid property type: ${definition::class}")
    }
    append(" ${types.joinToString("|")}")
    if (!required) append(" (optional)")
    appendLine()
    desc?.let { appendLine("$indent* Description\n$indent\t$it") }

    (p as? ObjectPropertyDefinition)?.properties?.takeIf { it.isNotEmpty() }?.let {
        appendLine("${indent}* Fields")
        it.forEach { (childName, childProperty) ->
            appendPropertyMarkdown(
                childProperty, defs,
                childName,
                p.required?.contains(childName) == true,
                depth + 1
            )
        }
    }
}

fun StringBuilder.appendPropertyShortJson(
    definition: PropertyDefinition,
    defs: Map<String, PropertyDefinition>,
    name: String? = null,
    required: Boolean = true,
    depth: Int = 0,
) {
    val indent = "\t".repeat(depth)
    val p = dereference(definition, defs)

    if (definition is StringPropertyDefinition && name == "type") {
        appendLine("${indent}\"type\": ${definition.constValue},")
        return
    }

    val desc = (p as? CommonSchemaAttributes)?.description
    val types = (p as? CommonSchemaAttributes)?.type
    val properties = (p as? PropertiesContainer)?.properties
    val req = (p as? PropertiesContainer)?.required
    val items = (p as? ArrayContainer)?.items
    val enum = when (p) {
        is ObjectPropertyDefinition -> p.enum
        is NumericPropertyDefinition -> p.enum
        is StringPropertyDefinition -> p.enum?.map { "\"$it\"" }
        is BooleanPropertyDefinition -> p.enum
        is ArrayPropertyDefinition -> p.enum
        is JsonSchema -> p.enum
        else -> null
    }

    desc?.let { appendLine("$indent// $it") }
    append(indent)
    name?.let { append("\"$it\": ") }
    types?.let { append(" ${it.joinToString("|")}") }
    enum?.let { append(" enum [${it.joinToString("|")}]") }
    if (!required) append(" (optional)")

    properties?.takeIf { it.isNotEmpty() }?.let {
        appendLine(" {")
        properties.forEach { (childName, childProperty) ->
            appendPropertyShortJson(
                childProperty, defs,
                childName,
                req?.contains(childName) == true,
                depth + 1
            )
        }
        append("$indent}")
    }
    items?.let {
        appendLine(" [")
        appendPropertyShortJson(items, defs, null, true, depth + 1)
        appendLine("${indent}\t...,")
        append("$indent]")
    }
    appendLine(",")
}