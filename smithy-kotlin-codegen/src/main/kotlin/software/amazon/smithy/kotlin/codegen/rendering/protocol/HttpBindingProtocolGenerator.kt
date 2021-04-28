/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */
package software.amazon.smithy.kotlin.codegen.rendering.protocol

import software.amazon.smithy.codegen.core.CodegenException
import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.codegen.core.SymbolReference
import software.amazon.smithy.kotlin.codegen.core.*
import software.amazon.smithy.kotlin.codegen.lang.toEscapedLiteral
import software.amazon.smithy.kotlin.codegen.model.buildSymbol
import software.amazon.smithy.kotlin.codegen.model.defaultValue
import software.amazon.smithy.kotlin.codegen.model.hasTrait
import software.amazon.smithy.kotlin.codegen.model.isEnum
import software.amazon.smithy.kotlin.codegen.model.isNotBoxed
import software.amazon.smithy.kotlin.codegen.model.knowledge.SerdeIndex
import software.amazon.smithy.kotlin.codegen.rendering.serde.*
import software.amazon.smithy.model.knowledge.HttpBinding
import software.amazon.smithy.model.shapes.*
import software.amazon.smithy.model.traits.*
import java.util.logging.Logger

/**
 * Abstract implementation useful for all HTTP protocols
 */
abstract class HttpBindingProtocolGenerator : ProtocolGenerator {
    private val LOGGER = Logger.getLogger(javaClass.name)

    override val applicationProtocol: ApplicationProtocol = ApplicationProtocol.createDefaultHttpApplicationProtocol()

    /**
     * The default serde format for timestamps.
     */
    protected abstract val defaultTimestampFormat: TimestampFormatTrait.Format

    /**
     * Returns HTTP binding resolver for protocol specified by input.
     */
    abstract fun getProtocolHttpBindingResolver(ctx: ProtocolGenerator.GenerationContext): HttpBindingResolver

    /**
     * Get the [HttpProtocolClientGenerator] to be used to render the implementation of the service client interface
     */
    abstract fun getHttpProtocolClientGenerator(ctx: ProtocolGenerator.GenerationContext): HttpProtocolClientGenerator

    /**
     * Get all of the middleware that should be installed into the operation's middleware stack (`SdkOperationExecution`)
     * This is the function that protocol client generators should invoke to get the fully resolved set of middleware
     * to be rendered (i.e. after integrations have had a chance to intercept). The default set of middleware for
     * a protocol can be overridden by [getDefaultHttpMiddleware].
     */
    fun getHttpMiddleware(ctx: ProtocolGenerator.GenerationContext): List<ProtocolMiddleware> {
        val defaultMiddleware = getDefaultHttpMiddleware(ctx)
        return ctx.integrations.fold(defaultMiddleware) { middleware, integration ->
            integration.customizeMiddleware(ctx, middleware)
        }
    }

    /**
     * Template method function that generators can override to return the _default_ set of middleware for the protocol
     */
    protected open fun getDefaultHttpMiddleware(ctx: ProtocolGenerator.GenerationContext): List<ProtocolMiddleware> = listOf()

    /**
     * Generate the set of [SdkFieldDescriptor]s for the types that require them.
     * @param ctx generation context
     * @param memberShape the shape representing the field descriptor
     * @param writer kotlin writer
     * @param memberTargetShape optional shape representing the type contained by a collection
     * @param namePostfix a string to postfix to the descriptor name, used for nested synthetic fields
     */
    protected abstract fun generateSdkFieldDescriptor(ctx: ProtocolGenerator.GenerationContext, memberShape: MemberShape, writer: KotlinWriter, memberTargetShape: Shape? = null, namePostfix: String = "")

    /**
     * Generate the set of traits on the [SdkObjectDescriptor] for the types that require them.
     */
    protected abstract fun generateSdkObjectDescriptorTraits(ctx: ProtocolGenerator.GenerationContext, objectShape: Shape, writer: KotlinWriter)

    /**
     * Sort and return [members] in the order they should be serialized in (sort order may not matter in all protocols)
     * By default they are sorted by the member name
     */
    protected open fun sortMembersForSerialization(ctx: ProtocolGenerator.GenerationContext, members: List<MemberShape>): List<MemberShape> {
        return members.sortedBy { it.memberName }
    }

    override fun generateSerializers(ctx: ProtocolGenerator.GenerationContext) {
        val resolver = getProtocolHttpBindingResolver(ctx)
        val httpOperations = resolver.bindingOperations()
        // render HttpSerialize for all operation inputs
        httpOperations.forEach { operation ->
            generateOperationSerializer(ctx, operation)
        }

        // generate serde for all shapes that appear as nested on any operation input
        // these types are `SdkSerializable` not `HttpSerialize`
        val serdeIndex = SerdeIndex.of(ctx.model)
        val shapesRequiringSerializers = serdeIndex.requiresDocumentSerializer(httpOperations)
        generateDocumentSerializers(ctx, shapesRequiringSerializers)
    }

    override fun generateDeserializers(ctx: ProtocolGenerator.GenerationContext) {
        val resolver = getProtocolHttpBindingResolver(ctx)
        // render HttpDeserialize for all operation outputs
        val httpOperations = resolver.bindingOperations()
        httpOperations.forEach { operation ->
            generateOperationDeserializer(ctx, operation)
        }

        // generate HttpDeserialize for exception types
        val modeledErrors = httpOperations.flatMap { it.errors }.map { ctx.model.expectShape(it) as StructureShape }.toSet()
        modeledErrors.forEach { generateExceptionDeserializer(ctx, it) }

        // generate serde for all shapes that appear as nested on any operation output
        // these types are independent document deserializers, they do not implement `HttpDeserialize`
        val serdeIndex = SerdeIndex.of(ctx.model)
        val shapesRequiringDeserializers = serdeIndex.requiresDocumentDeserializer(httpOperations)
        generateDocumentDeserializers(ctx, shapesRequiringDeserializers)
    }

    override fun generateProtocolClient(ctx: ProtocolGenerator.GenerationContext) {
        val symbol = ctx.symbolProvider.toSymbol(ctx.service)
        ctx.delegator.useFileWriter("Default${symbol.name}.kt", ctx.settings.pkg.name) { writer ->
            val clientGenerator = getHttpProtocolClientGenerator(ctx)
            clientGenerator.render(writer)
        }
    }

    /**
     * Generate `SdkSerializable` serializer for all shapes in the set
     */
    private fun generateDocumentSerializers(ctx: ProtocolGenerator.GenerationContext, shapes: Set<Shape>) {
        for (shape in shapes) {
            val symbol = ctx.symbolProvider.toSymbol(shape)

            val serializerSymbol = buildSymbol {
                definitionFile = "${symbol.documentSerializerName()}.kt"
                name = symbol.documentSerializerName()
                namespace = "${ctx.settings.pkg.name}.transform"

                // serializer class for the shape takes the shape's symbol as input
                // ensure we get an import statement to the symbol from the .model package
                reference(symbol, SymbolReference.ContextOption.DECLARE)
            }

            ctx.delegator.useShapeWriter(serializerSymbol) { writer ->
                renderDocumentSerializer(ctx, symbol, shape, serializerSymbol, writer)
            }
        }
    }

    /**
     * Actually renders the `SdkSerializable` implementation for the given symbol/shape
     * @param ctx The codegen context
     * @param symbol The symbol to generate a serializer implementation for
     * @param shape The corresponding shape
     * @param serializerSymbol The symbol for the serializer class that wraps the [symbol]
     * @param writer The codegen writer to render to
     */
    private fun renderDocumentSerializer(
        ctx: ProtocolGenerator.GenerationContext,
        symbol: Symbol,
        shape: Shape,
        serializerSymbol: Symbol,
        writer: KotlinWriter
    ) {
        importSerdePackage(writer)

        writer.write("")
            .openBlock("internal class #T(val input: #T) : SdkSerializable {", serializerSymbol, symbol)
            .call {
                renderSerdeCompanionObject(ctx, shape, shape.members().toList(), writer)
            }
            .call {
                writer.withBlock("override fun serialize(serializer: Serializer) {", "}") {
                    val sortedMembers = sortMembersForSerialization(ctx, shape.members().toList())
                    if (shape.isUnionShape) {
                        SerializeUnionGenerator(ctx, sortedMembers, writer, defaultTimestampFormat).render()
                    } else {
                        SerializeStructGenerator(ctx, sortedMembers, writer, defaultTimestampFormat).render()
                    }
                }
            }
            .closeBlock("}")
    }

    /**
     * Generate request serializer (HttpSerialize) for an operation
     */
    private fun generateOperationSerializer(ctx: ProtocolGenerator.GenerationContext, op: OperationShape) {
        if (!op.input.isPresent) {
            return
        }

        val inputShape = ctx.model.expectShape(op.input.get())
        val inputSymbol = ctx.symbolProvider.toSymbol(inputShape)

        // operation input shapes could be re-used across one or more operations. The protocol details may
        // be different though (e.g. uri/method). We need to generate a serializer/deserializer per/operation
        // NOT per input/output shape
        val serializerSymbol = buildSymbol {
            definitionFile = "${op.serializerName()}.kt"
            name = op.serializerName()
            namespace = "${ctx.settings.pkg.name}.transform"

            reference(inputSymbol, SymbolReference.ContextOption.DECLARE)
        }

        val resolver = getProtocolHttpBindingResolver(ctx)
        val httpTrait = resolver.httpTrait(op)
        val requestBindings = resolver.requestBindings(op)
        ctx.delegator.useShapeWriter(serializerSymbol) { writer ->
            // import all of http, http.request, and serde packages. All serializers requires one or more of the symbols
            // and most require quite a few. Rather than try and figure out which specific ones are used just take them
            // all to ensure all the various DSL builders are available, etc
            importSerdePackage(writer)
            writer.addImport(KotlinDependency.CLIENT_RT_HTTP.namespace, "*")
            writer.addImport("${KotlinDependency.CLIENT_RT_HTTP.namespace}.request", "*")
            writer.addImport(RuntimeTypes.Http.HttpSerialize)

            writer.write("")
                .openBlock("internal class #T(): HttpSerialize<#T> {", serializerSymbol, inputSymbol)
                .call {
                    val objectShape = ctx.model.expectShape(op.input.get())
                    val memberShapes = requestBindings.filter { it.location == HttpBinding.Location.DOCUMENT }.map { it.member }
                    renderSerdeCompanionObject(ctx, objectShape, memberShapes, writer)
                }
                .call {
                    val contentType = resolver.determineRequestContentType(op)
                    renderHttpSerialize(ctx, httpTrait, contentType, requestBindings, inputSymbol, writer)
                }
                .closeBlock("}")
        }
    }

    /**
     * Generate the field descriptors
     *
     * @param ctx context
     * @param objectShape The top-level shape for the structure, used to generate the object descriptor
     * @param memberShapes The shapes from which to generate field descriptors
     * @param writer kotlin writer
     */
    private fun renderSerdeCompanionObject(
        ctx: ProtocolGenerator.GenerationContext,
        objectShape: Shape?,
        memberShapes: List<MemberShape>,
        writer: KotlinWriter
    ) {
        if (memberShapes.isEmpty()) return
        writer.write("")
            .withBlock("companion object {", "}") {
                val sortedMembers = memberShapes.sortedBy { it.memberName }
                for (member in sortedMembers) {
                    generateSdkFieldDescriptor(ctx, member, writer)

                    val memberTarget = ctx.model.expectShape(member.target)
                    val nestedMember = memberTarget.childShape(ctx)
                    if (nestedMember?.isContainerShape() == true) {
                        renderNestedFieldDescriptors(ctx, member, nestedMember, 0, writer)
                    }
                }
                writer.withBlock("private val OBJ_DESCRIPTOR = SdkObjectDescriptor.build {", "}") {
                    objectShape?.let { generateSdkObjectDescriptorTraits(ctx, it, writer) }

                    for (member in sortedMembers) {
                        write("field(#L)", member.descriptorName())
                    }
                }
            }
            .write("")
    }

    /**
     * Generate field descriptors for nested serialization types.
     */
    private fun renderNestedFieldDescriptors(ctx: ProtocolGenerator.GenerationContext, rootShape: MemberShape, childShape: Shape, level: Int, writer: KotlinWriter) {
        generateSdkFieldDescriptor(ctx, rootShape, writer, childShape, "_C$level")

        val nestedMember = childShape.childShape(ctx)
        if (nestedMember?.isContainerShape() == true) renderNestedFieldDescriptors(ctx, rootShape, nestedMember, level + 1, writer)
    }

    // replace labels with any path bindings
    private fun resolveUriPath(
        ctx: ProtocolGenerator.GenerationContext,
        httpTrait: HttpTrait,
        pathBindings: List<HttpBindingDescriptor>,
        writer: KotlinWriter
    ): String {
        return httpTrait.uri.segments.joinToString(
            separator = "/",
            prefix = "/",
            postfix = "",
            transform = { segment ->
                if (segment.isLabel) {
                    // spec dictates member name and label name MUST be the same
                    val binding = pathBindings.find { binding ->
                        binding.memberName == segment.content
                    } ?: throw CodegenException("failed to find corresponding member for httpLabel `${segment.content}")

                    // shape must be string, number, boolean, or timestamp
                    val targetShape = ctx.model.expectShape(binding.member.target)
                    if (targetShape.isTimestampShape) {
                        writer.addImport(RuntimeTypes.Core.TimestampFormat)
                        val resolver = getProtocolHttpBindingResolver(ctx)
                        val tsFormat = resolver.determineTimestampFormat(
                            binding.member,
                            HttpBinding.Location.LABEL,
                            defaultTimestampFormat
                        )
                        val tsLabel = formatInstant("input.${binding.member.defaultName()}?", tsFormat, forceString = true)
                        "\${$tsLabel}"
                    } else {
                        "\${input.${binding.member.defaultName()}}"
                    }
                } else {
                    // literal
                    segment.content.toEscapedLiteral()
                }
            }
        )
    }

    private fun renderHttpSerialize(
        ctx: ProtocolGenerator.GenerationContext,
        httpTrait: HttpTrait,
        contentType: String,
        requestBindings: List<HttpBindingDescriptor>,
        inputSymbol: Symbol,
        writer: KotlinWriter
    ) {
        writer.addImport(RuntimeTypes.Core.ExecutionContext)

        writer.openBlock("override suspend fun serialize(context: #T, input: #T): HttpRequestBuilder {", RuntimeTypes.Core.ExecutionContext, inputSymbol)
            .write("val builder = HttpRequestBuilder()")
            .write("builder.method = HttpMethod.#L", httpTrait.method.toUpperCase())
            .write("")
            .call {
                // URI components
                val pathBindings = requestBindings.filter { it.location == HttpBinding.Location.LABEL }
                val queryBindings = requestBindings.filter { it.location == HttpBinding.Location.QUERY }
                val resolvedPath = resolveUriPath(ctx, httpTrait, pathBindings, writer)

                writer.withBlock("builder.url {", "}") {
                    // Path
                    write("path = \"#L\"", resolvedPath)

                    // Query Parameters
                    renderQueryParameters(ctx, httpTrait.uri.queryLiterals, queryBindings, writer)
                }
            }
            .write("")
            .call {
                // headers
                val headerBindings = requestBindings
                    .filter { it.location == HttpBinding.Location.HEADER }
                    .sortedBy { it.memberName }

                val prefixHeaderBindings = requestBindings
                    .filter { it.location == HttpBinding.Location.PREFIX_HEADERS }

                if (headerBindings.isNotEmpty() || prefixHeaderBindings.isNotEmpty()) {
                    writer.withBlock("builder.headers {", "}") {
                        renderStringValuesMapParameters(ctx, headerBindings, writer)
                        prefixHeaderBindings.forEach {
                            writer.withBlock("input.${it.member.defaultName()}?.filter { it.value != null }?.forEach { (key, value) ->", "}") {
                                write("append(\"#L\$key\", value!!)", it.locationName)
                            }
                        }
                    }
                }
            }
            .write("")
            .callIf(hasHttpBody(requestBindings)) {
                // payload member(s)
                val httpPayload = requestBindings.firstOrNull { it.location == HttpBinding.Location.PAYLOAD }
                if (httpPayload != null) {
                    renderExplicitHttpPayloadSerializer(ctx, httpPayload, writer)
                } else {
                    // Unbound document members that should be serialized into the document format for the protocol.
                    // The generated code is the same across protocols and the serialization provider instance
                    // passed into the function is expected to handle the formatting required by the protocol
                    val documentMembers = requestBindings
                        .filter { it.location == HttpBinding.Location.DOCUMENT }
                        .sortedBy { it.memberName }

                    renderUnboundPayloadSerde(ctx, documentMembers, writer)
                }

                // render content-type as last thing once the body has been set
                writer.openBlock("if (builder.body !is HttpBody.Empty) {", "}") {
                    writer.write("builder.headers[\"Content-Type\"] = #S", contentType)
                }
            }
            .write("return builder")
            .closeBlock("}")
    }

    private fun renderQueryParameters(
        ctx: ProtocolGenerator.GenerationContext,
        // literals in the URI
        queryLiterals: Map<String, String>,
        // shape bindings
        queryBindings: List<HttpBindingDescriptor>,
        writer: KotlinWriter
    ) {

        if (queryBindings.isEmpty() && queryLiterals.isEmpty()) return

        writer.withBlock("parameters {", "}") {
            queryLiterals.forEach { (key, value) ->
                writer.write("append(#S, #S)", key, value)
            }
            renderStringValuesMapParameters(ctx, queryBindings, writer)
        }
    }

    // shared implementation for rendering members that belong to StringValuesMap (e.g. Header or Query parameters)
    private fun renderStringValuesMapParameters(
        ctx: ProtocolGenerator.GenerationContext,
        bindings: List<HttpBindingDescriptor>,
        writer: KotlinWriter
    ) {
        val resolver = getProtocolHttpBindingResolver(ctx)
        HttpStringValuesMapSerializer(ctx, bindings, resolver, defaultTimestampFormat).render(writer)
    }

    /**
     * Render serialization for member(s) not bound to any other http traits, these members are serialized using
     * the protocol specific document format
     *
     * @param ctx The code generation context
     * @param members The document members to serialize
     * @param writer The code writer to render to
     */
    private fun renderUnboundPayloadSerde(
        ctx: ProtocolGenerator.GenerationContext,
        members: List<HttpBindingDescriptor>,
        writer: KotlinWriter
    ) {
        if (members.isEmpty()) return

        writer.addImport(RuntimeTypes.Http.ByteArrayContent)
        writer.write("val serializer = context.serializer()")
            .call {
                val renderForMembers = sortMembersForSerialization(ctx, members.map { it.member })
                SerializeStructGenerator(ctx, renderForMembers, writer, defaultTimestampFormat).render()
            }
            .write("")
            .write("builder.body = ByteArrayContent(serializer.toByteArray())")
    }

    /**
     * Render serialization for a member bound with the `httpPayload` trait
     *
     * @param ctx The code generation context
     * @param binding The explicit payload binding
     * @param writer The code writer to render to
     */
    private fun renderExplicitHttpPayloadSerializer(
        ctx: ProtocolGenerator.GenerationContext,
        binding: HttpBindingDescriptor,
        writer: KotlinWriter
    ) {
        // explicit payload member as the sole payload
        val memberName = binding.member.defaultName()
        writer.openBlock("if (input.#L != null) {", memberName)

        val target = ctx.model.expectShape(binding.member.target)

        when (target.type) {
            ShapeType.BLOB -> {
                val isBinaryStream = ctx.model.expectShape(binding.member.target).hasTrait<StreamingTrait>()
                if (isBinaryStream) {
                    writer.write("builder.body = input.#L.toHttpBody() ?: HttpBody.Empty", memberName)
                } else {
                    writer.addImport("${KotlinDependency.CLIENT_RT_HTTP.namespace}.content", "ByteArrayContent")
                    writer.write("builder.body = ByteArrayContent(input.#L)", memberName)
                }
            }
            ShapeType.STRING -> {
                writer.addImport("${KotlinDependency.CLIENT_RT_HTTP.namespace}.content", "ByteArrayContent")
                val contents = if (target.isEnum) {
                    "$memberName.value"
                } else {
                    memberName
                }
                writer.write("builder.body = ByteArrayContent(input.#L.toByteArray())", contents)
            }
            ShapeType.STRUCTURE, ShapeType.UNION -> {
                // delegate to the member serializer
                writer.addImport("${KotlinDependency.CLIENT_RT_HTTP.namespace}.content", "ByteArrayContent")
                val memberSymbol = ctx.symbolProvider.toSymbol(binding.member)
                writer.write("val serializer = context.serializer()")
                    .write("#L(input.#L).serialize(serializer)", memberSymbol.documentSerializerName(), memberName)
                    .write("builder.body = ByteArrayContent(serializer.toByteArray())")
            }
            ShapeType.DOCUMENT -> {
                // TODO - deal with document members
            }
            else -> throw CodegenException("member shape ${binding.member} serializer not implemented yet")
        }
        writer.closeBlock("}")
    }

    /**
     * Generate request deserializer (HttpDeserialize) for an operation
     */
    private fun generateOperationDeserializer(ctx: ProtocolGenerator.GenerationContext, op: OperationShape) {
        if (!op.output.isPresent) {
            return
        }

        val outputShape = ctx.model.expectShape(op.output.get())
        val outputSymbol = ctx.symbolProvider.toSymbol(outputShape)

        // operation output shapes could be re-used across one or more operations. The protocol details may
        // be different though (e.g. uri/method). We need to generate a serializer/deserializer per/operation
        // NOT per input/output shape
        val deserializerSymbol = buildSymbol {
            definitionFile = "${op.deserializerName()}.kt"
            name = op.deserializerName()
            namespace = "${ctx.settings.pkg.name}.transform"

            reference(outputSymbol, SymbolReference.ContextOption.DECLARE)
        }

        val resolver = getProtocolHttpBindingResolver(ctx)
        val responseBindings = resolver.responseBindings(op)
        ctx.delegator.useShapeWriter(deserializerSymbol) { writer ->
            // import all of http, http.response , and serde packages. All serializers requires one or more of the symbols
            // and most require quite a few. Rather than try and figure out which specific ones are used just take them
            // all to ensure all the various DSL builders are available, etc
            importSerdePackage(writer)
            writer.addImport(KotlinDependency.CLIENT_RT_HTTP.namespace, "*")
            writer.addImport(RuntimeTypes.Http.HttpResponse)
            writer.addImport(RuntimeTypes.Http.HttpDeserialize)

            writer.write("")
                .openBlock(
                    "internal class #T(): HttpDeserialize<#T> {",
                    deserializerSymbol,
                    outputSymbol
                )
                .write("")
                .call {
                    val objectShape = ctx.model.expectShape(op.output.get())
                    val memberShapes = responseBindings
                        .filter { it.location == HttpBinding.Location.DOCUMENT }
                        .map { it.member }
                    renderSerdeCompanionObject(ctx, objectShape, memberShapes, writer)
                }
                .write("")
                .call {
                    renderHttpDeserialize(ctx, outputSymbol, responseBindings, writer)
                }
                .closeBlock("}")
        }
    }

    /**
     * Generate HttpDeserialize for a modeled error (exception)
     */
    private fun generateExceptionDeserializer(ctx: ProtocolGenerator.GenerationContext, shape: StructureShape) {
        val outputSymbol = ctx.symbolProvider.toSymbol(shape)

        val deserializerSymbol = buildSymbol {
            val deserializerName = "${outputSymbol.name}Deserializer"
            definitionFile = "$deserializerName.kt"
            name = deserializerName
            namespace = "${ctx.settings.pkg.name}.transform"
            reference(outputSymbol, SymbolReference.ContextOption.DECLARE)
        }

        ctx.delegator.useShapeWriter(deserializerSymbol) { writer ->
            importSerdePackage(writer)
            writer.addImport(KotlinDependency.CLIENT_RT_HTTP.namespace, "*")
            writer.addImport("${KotlinDependency.CLIENT_RT_HTTP.namespace}.response", "HttpResponse")
            writer.addImport("${KotlinDependency.CLIENT_RT_HTTP.namespace}.operation", "HttpDeserialize")
            writer.addImport("DeserializationProvider", KotlinDependency.CLIENT_RT_SERDE)

            writer.write("")
                .openBlock("internal class #T() : HttpDeserialize<#L> {", deserializerSymbol, outputSymbol.name)
                .write("")
                .call {
                    val documentMembers = shape.members().filterNot {
                        it.hasTrait<HttpHeaderTrait>() || it.hasTrait<HttpPrefixHeadersTrait>()
                    }

                    renderSerdeCompanionObject(ctx, shape, documentMembers, writer)
                }
                .write("")
                .call {
                    val resolver = getProtocolHttpBindingResolver(ctx)
                    val responseBindings = resolver.responseBindings(shape)
                    renderHttpDeserialize(ctx, outputSymbol, responseBindings, writer)
                }
                .closeBlock("}")
        }
    }

    private fun renderHttpDeserialize(
        ctx: ProtocolGenerator.GenerationContext,
        outputSymbol: Symbol,
        responseBindings: List<HttpBindingDescriptor>,
        writer: KotlinWriter
    ) {
        writer.addImport(RuntimeTypes.Core.ExecutionContext)

        writer.openBlock(
            "override suspend fun deserialize(context: #T, response: HttpResponse): #T {",
            RuntimeTypes.Core.ExecutionContext,
            outputSymbol
        )
            .write("val builder = #T.builder()", outputSymbol)
            .write("")
            .call {
                // headers
                val headerBindings = responseBindings
                    .filter { it.location == HttpBinding.Location.HEADER }
                    .sortedBy { it.memberName }

                renderDeserializeHeaders(ctx, headerBindings, writer)

                // prefix headers
                // spec: "Only a single structure member can be bound to httpPrefixHeaders"
                responseBindings.firstOrNull { it.location == HttpBinding.Location.PREFIX_HEADERS }
                    ?.let {
                        renderDeserializePrefixHeaders(ctx, it, writer)
                    }
            }
            .write("")
            .call {
                // document members
                // payload member(s)
                val httpPayload = responseBindings.firstOrNull { it.location == HttpBinding.Location.PAYLOAD }
                if (httpPayload != null) {
                    renderExplicitHttpPayloadDeserializer(ctx, httpPayload, writer)
                } else {
                    // Unbound document members that should be deserialized from the document format for the protocol.
                    // The generated code is the same across protocols and the serialization provider instance
                    // passed into the function is expected to handle the formatting required by the protocol
                    val documentMembers = responseBindings
                        .filter { it.location == HttpBinding.Location.DOCUMENT }
                        .sortedBy { it.memberName }
                        .map { it.member }

                    if (documentMembers.isNotEmpty()) {
                        // FIXME - we should not be slurping the entire contents into memory, instead our deserializers
                        // should work off of an SdkByteReadChannel
                        writer.write("val payload = response.body.readAll()")
                        writer.withBlock("if (payload != null) {", "}") {
                            writer.write("val deserializer = context.deserializer(payload)")
                            DeserializeStructGenerator(ctx, documentMembers, writer, defaultTimestampFormat).render()
                        }
                    }
                }
            }
            .call {
                responseBindings.firstOrNull { it.location == HttpBinding.Location.RESPONSE_CODE }
                    ?.let {
                        renderDeserializeResponseCode(ctx, it, writer)
                    }
            }
            .write("return builder.build()")
            .closeBlock("}")
    }

    /**
     * Render mapping http response code value to response type.
     */
    private fun renderDeserializeResponseCode(ctx: ProtocolGenerator.GenerationContext, binding: HttpBindingDescriptor, writer: KotlinWriter) {
        val memberName = binding.member.defaultName()
        val memberTarget = ctx.model.expectShape(binding.member.target)

        check(memberTarget.type == ShapeType.INTEGER) { "Unexpected target type in response code deserialization: ${memberTarget.id} (${memberTarget.type})" }

        writer.write("builder.#L = response.status.value", memberName)
    }

    /**
     * Render deserialization of all members bound to a response header
     */
    private fun renderDeserializeHeaders(
        ctx: ProtocolGenerator.GenerationContext,
        bindings: List<HttpBindingDescriptor>,
        writer: KotlinWriter
    ) {
        val resolver = getProtocolHttpBindingResolver(ctx)
        bindings.forEach { hdrBinding ->
            val memberTarget = ctx.model.expectShape(hdrBinding.member.target)
            val memberName = hdrBinding.member.defaultName()
            val headerName = hdrBinding.locationName

            val targetSymbol = ctx.symbolProvider.toSymbol(hdrBinding.member)
            val defaultValuePostfix = if (targetSymbol.isNotBoxed && targetSymbol.defaultValue() != null) {
                " ?: ${targetSymbol.defaultValue()}"
            } else {
                ""
            }

            when (memberTarget) {
                is NumberShape -> {
                    writer.write(
                        "builder.#L = response.headers[#S]?.#L$defaultValuePostfix",
                        memberName, headerName, stringToNumber(memberTarget)
                    )
                }
                is BooleanShape -> {
                    writer.write(
                        "builder.#L = response.headers[#S]?.toBoolean()$defaultValuePostfix",
                        memberName, headerName
                    )
                }
                is BlobShape -> {
                    writer.addImport("decodeBase64", KotlinDependency.CLIENT_RT_UTILS)
                    writer.write("builder.#L = response.headers[#S]?.decodeBase64()", memberName, headerName)
                }
                is StringShape -> {
                    when {
                        memberTarget.isEnum -> {
                            val enumSymbol = ctx.symbolProvider.toSymbol(memberTarget)
                            writer.addImport(enumSymbol)
                            writer.write(
                                "builder.#L = response.headers[#S]?.let { #L.fromValue(it) }",
                                memberName,
                                headerName,
                                enumSymbol.name
                            )
                        }
                        memberTarget.hasTrait<MediaTypeTrait>() -> {
                            writer.addImport("decodeBase64", KotlinDependency.CLIENT_RT_UTILS)
                            writer.write("builder.#L = response.headers[#S]?.decodeBase64()", memberName, headerName)
                        }
                        else -> {
                            writer.write("builder.#L = response.headers[#S]", memberName, headerName)
                        }
                    }
                }
                is TimestampShape -> {
                    val tsFormat = resolver.determineTimestampFormat(
                        hdrBinding.member,
                        HttpBinding.Location.HEADER,
                        defaultTimestampFormat
                    )
                    writer.addImport(RuntimeTypes.Core.Instant)

                    writer.write(
                        "builder.#L = response.headers[#S]?.let { #L }",
                        memberName, headerName, parseInstant("it", tsFormat)
                    )
                }
                is CollectionShape -> {
                    // member > boolean, number, string, or timestamp
                    // headers are List<String>, get the internal mapping function contents (if any) to convert
                    // to the target symbol type

                    // we also have to handle multiple comma separated values (e.g. 'X-Foo': "1, 2, 3"`)
                    var splitFn = "splitHeaderListValues"
                    val conversion = when (val collectionMemberTarget = ctx.model.expectShape(memberTarget.member.target)) {
                        is BooleanShape -> "it.toBoolean()"
                        is NumberShape -> "it." + stringToNumber(collectionMemberTarget)
                        is TimestampShape -> {
                            val tsFormat = resolver.determineTimestampFormat(
                                hdrBinding.member,
                                HttpBinding.Location.HEADER,
                                defaultTimestampFormat
                            )
                            if (tsFormat == TimestampFormatTrait.Format.HTTP_DATE) {
                                splitFn = "splitHttpDateHeaderListValues"
                            }
                            writer.addImport(RuntimeTypes.Core.Instant)
                            parseInstant("it", tsFormat)
                        }
                        is StringShape -> {
                            when {
                                collectionMemberTarget.isEnum -> {
                                    val enumSymbol = ctx.symbolProvider.toSymbol(collectionMemberTarget)
                                    writer.addImport(enumSymbol)
                                    "${enumSymbol.name}.fromValue(it)"
                                }
                                collectionMemberTarget.hasTrait<MediaTypeTrait>() -> {
                                    writer.addImport("decodeBase64", KotlinDependency.CLIENT_RT_UTILS)
                                    "it.decodeBase64()"
                                }
                                else -> ""
                            }
                        }
                        else -> throw CodegenException("invalid member type for header collection: binding: $hdrBinding; member: $memberName")
                    }

                    val toCollectionType = when {
                        memberTarget.isListShape -> ""
                        memberTarget.isSetShape -> "?.toSet()"
                        else -> throw CodegenException("unknown collection shape: $memberTarget")
                    }

                    val mapFn = if (conversion.isNotEmpty()) "?.map { $conversion }" else ""

                    writer.addImport("${KotlinDependency.CLIENT_RT_HTTP.namespace}.util", splitFn)
                    writer.write("builder.#L = response.headers.getAll(#S)?.flatMap(::$splitFn)${mapFn}$toCollectionType", memberName, headerName)
                }
                else -> throw CodegenException("unknown deserialization: header binding: $hdrBinding; member: `$memberName`")
            }
        }
    }

    private fun renderDeserializePrefixHeaders(
        ctx: ProtocolGenerator.GenerationContext,
        binding: HttpBindingDescriptor,
        writer: KotlinWriter
    ) {
        // prefix headers MUST target string or collection-of-string
        val targetShape = ctx.model.expectShape(binding.member.target) as? MapShape
            ?: throw CodegenException("prefixHeader bindings can only be attached to Map shapes")

        val targetValueShape = ctx.model.expectShape(targetShape.value.target)
        val targetValueSymbol = ctx.symbolProvider.toSymbol(targetValueShape)
        val prefix = binding.locationName
        val memberName = binding.member.defaultName()

        val keyCollName = "keysFor${memberName.capitalize()}"
        val filter = if (prefix?.isNotEmpty() == true) ".filter { it.startsWith(\"$prefix\") }" else ""

        writer.write("val $keyCollName = response.headers.names()$filter")
        writer.openBlock("if ($keyCollName.isNotEmpty()) {")
            .write("val map = mutableMapOf<String, ${targetValueSymbol.name}>()")
            .openBlock("for (hdrKey in $keyCollName) {")
            .call {
                val getFn = when (targetValueShape) {
                    is StringShape -> "[hdrKey]"
                    is ListShape -> ".getAll(hdrKey)"
                    is SetShape -> ".getAll(hdrKey)?.toSet()"
                    else -> throw CodegenException("invalid httpPrefixHeaders usage on ${binding.member}")
                }
                // get()/getAll() returns String? or List<String>?, this shouldn't ever trigger the continue though...
                writer.write("val el = response.headers$getFn ?: continue")
                if (prefix?.isNotEmpty() == true) {
                    writer.write("val key = hdrKey.removePrefix(#S)", prefix)
                    writer.write("map[key] = el")
                } else {
                    writer.write("map[hdrKey] = el")
                }
            }
            .closeBlock("}")
            .write("builder.$memberName = map")
            .closeBlock("} else {")
            .indent()
            .write("builder.$memberName = emptyMap()")
            .dedent()
            .write("}")
    }

    private fun renderExplicitHttpPayloadDeserializer(
        ctx: ProtocolGenerator.GenerationContext,
        binding: HttpBindingDescriptor,
        writer: KotlinWriter
    ) {
        val memberName = binding.member.defaultName()
        val target = ctx.model.expectShape(binding.member.target)
        val targetSymbol = ctx.symbolProvider.toSymbol(target)
        when (target.type) {
            ShapeType.STRING -> {
                writer.write("val contents = response.body.readAll()?.decodeToString()")
                if (target.isEnum) {
                    writer.addImport(targetSymbol)
                    writer.write("builder.$memberName = contents?.let { ${targetSymbol.name}.fromValue(it) }")
                } else {
                    writer.write("builder.$memberName = contents")
                }
            }
            ShapeType.BLOB -> {
                val isBinaryStream = target.hasTrait<StreamingTrait>()
                val conversion = if (isBinaryStream) {
                    "toByteStream()"
                } else {
                    "readAll()"
                }
                writer.write("builder.$memberName = response.body.$conversion")
            }
            ShapeType.STRUCTURE, ShapeType.UNION -> {
                // delegate to the member deserializer
                writer.write("val payload = response.body.readAll()")
                writer.openBlock("if (payload != null) {")
                    .write("val deserializer = context.deserializer(payload)")
                    .write("builder.$memberName = #L().deserialize(deserializer)", targetSymbol.documentDeserializerName())
                    .closeBlock("}")
            }
            ShapeType.DOCUMENT -> {
                // TODO - implement document support
            }
            else -> throw CodegenException("member shape ${binding.member} deserializer not implemented")
        }

        writer.openBlock("")
            .closeBlock("")
    }

    /**
     * Generate deserializer for all shapes in the set
     */
    private fun generateDocumentDeserializers(ctx: ProtocolGenerator.GenerationContext, shapes: Set<Shape>) {
        for (shape in shapes) {
            val symbol = ctx.symbolProvider.toSymbol(shape)
            val deserializerSymbol = buildSymbol {
                definitionFile = "${symbol.documentDeserializerName()}.kt"
                name = symbol.documentDeserializerName()
                namespace = "${ctx.settings.pkg.name}.transform"

                // deserializer class for the shape outputs the shape's symbol
                // ensure we get an import statement to the symbol from the .model package
                reference(symbol, SymbolReference.ContextOption.DECLARE)
            }

            ctx.delegator.useShapeWriter(deserializerSymbol) { writer ->
                renderDocumentDeserializer(ctx, symbol, shape, deserializerSymbol, writer)
            }
        }
    }

    /**
     * Actually renders the deserializer implementation for the given symbol/shape
     * @param ctx The codegen context
     * @param symbol The symbol to generate a deserializer implementation for
     * @param shape The corresponding shape
     * @param deserializerSymbol The deserializer symbol itself being generated
     * @param writer The codegen writer to render to
     */
    private fun renderDocumentDeserializer(
        ctx: ProtocolGenerator.GenerationContext,
        symbol: Symbol,
        shape: Shape,
        deserializerSymbol: Symbol,
        writer: KotlinWriter
    ) {
        importSerdePackage(writer)

        writer.write("")
            .openBlock("internal class #T {", deserializerSymbol)
            .call {
                renderSerdeCompanionObject(ctx, shape, shape.members().toList(), writer)
            }
            .call {

                if (shape.isUnionShape) {
                    writer.withBlock("suspend fun deserialize(deserializer: Deserializer): ${symbol.name} {", "}") {
                        writer.write("var value: ${symbol.name}? = null")
                        DeserializeUnionGenerator(ctx, symbol.name, shape.members().toList(), writer, defaultTimestampFormat).render()
                        writer.write("return value ?: throw DeserializationException(\"Deserialized value unexpectedly null: ${symbol.name}\")")
                    }
                        .closeBlock("}")
                } else {
                    writer.withBlock("suspend fun deserialize(deserializer: Deserializer): ${symbol.name} {", "}") {
                        writer.write("val builder = ${symbol.name}.builder()")
                        DeserializeStructGenerator(ctx, shape.members().toList(), writer, defaultTimestampFormat).render()
                        writer.write("return builder.build()")
                    }
                        .closeBlock("}")
                }
            }
    }
}

// import CLIENT-RT.*
internal fun importSerdePackage(writer: KotlinWriter) {
    writer.addImport(KotlinDependency.CLIENT_RT_SERDE.namespace, "*")
    writer.dependencies.addAll(KotlinDependency.CLIENT_RT_SERDE.dependencies)
}

// return the conversion function to use to convert a Kotlin string to a given number shape
internal fun stringToNumber(shape: NumberShape): String = when (shape.type) {
    ShapeType.BYTE -> "toByte()"
    ShapeType.SHORT -> "toShort()"
    ShapeType.INTEGER -> "toInt()"
    ShapeType.LONG -> "toLong()"
    ShapeType.FLOAT -> "toFloat()"
    ShapeType.DOUBLE -> "toDouble()"
    else -> throw CodegenException("unknown number shape: $shape")
}

// test if the request bindings have any members bound to the HTTP payload (body)
private fun hasHttpBody(requestBindings: List<HttpBindingDescriptor>): Boolean =
    requestBindings.any { it.location == HttpBinding.Location.PAYLOAD || it.location == HttpBinding.Location.DOCUMENT }

// Returns [true] if the shape can contain other shapes.
private fun Shape.isContainerShape() = when (this) {
    is CollectionShape,
    is MapShape -> true
    else -> false
}

// Returns [Shape] of the child member of the passed Shape is a collection type or null if not collection type.
private fun Shape.childShape(ctx: ProtocolGenerator.GenerationContext): Shape? = when (this) {
    is CollectionShape -> ctx.model.expectShape(this.member.target)
    is MapShape -> ctx.model.expectShape(this.value.target)
    else -> null
}