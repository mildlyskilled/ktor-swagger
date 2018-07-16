package de.nielsfalk.ktor.swagger

import de.nielsfalk.ktor.swagger.version.shared.CommonBase
import de.nielsfalk.ktor.swagger.version.shared.Group
import de.nielsfalk.ktor.swagger.version.shared.ModelName
import de.nielsfalk.ktor.swagger.version.shared.OperationBase
import de.nielsfalk.ktor.swagger.version.shared.Parameter
import de.nielsfalk.ktor.swagger.version.shared.ParameterInputType
import de.nielsfalk.ktor.swagger.version.v2.Swagger
import de.nielsfalk.ktor.swagger.version.v3.OpenApi
import io.ktor.application.Application
import io.ktor.application.ApplicationCall
import io.ktor.application.ApplicationFeature
import io.ktor.application.call
import io.ktor.client.call.TypeInfo
import io.ktor.client.call.typeInfo
import io.ktor.http.HttpMethod
import io.ktor.locations.Location
import io.ktor.pipeline.PipelineContext
import io.ktor.response.respond
import io.ktor.response.respondRedirect
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.util.AttributeKey
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import de.nielsfalk.ktor.swagger.version.v2.Operation as OperationV2
import de.nielsfalk.ktor.swagger.version.v2.Response as ResponseV2
import de.nielsfalk.ktor.swagger.version.v3.Operation as OperationV3
import de.nielsfalk.ktor.swagger.version.v3.Response as ResponseV3

class SwaggerSupport(
    val swagger: Swagger?,
    val openApi: OpenApi?
) {
    companion object Feature : ApplicationFeature<Application, SwaggerUiConfiguration, SwaggerSupport> {
        override val key = AttributeKey<SwaggerSupport>("SwaggerSupport")

        override fun install(pipeline: Application, configure: SwaggerUiConfiguration.() -> Unit): SwaggerSupport {
            val (path, forwardRoot, provideUi, swagger, openApi) = SwaggerUiConfiguration().apply(configure)
            val feature = SwaggerSupport(swagger, openApi)
            pipeline.routing {
                get("/$path") {
                    redirect(path)
                }
                val ui = if (provideUi) SwaggerUi() else null
                get("/$path/{fileName}") {
                    val filename = call.parameters["fileName"]
                    if (filename == "swagger.json" && swagger != null) {
                        call.respond(swagger)
                    } else if (filename == "openapi.json" && openApi != null) {
                        call.respond(openApi)
                    } else {
                        ui?.serve(filename, call)
                    }
                }
                if (forwardRoot) {
                    get("/") {
                        redirect(path)
                    }
                }
            }
            return feature
        }

        private suspend fun PipelineContext<Unit, ApplicationCall>.redirect(path: String) {
            call.respondRedirect("/$path/index.html?url=swagger.json")
        }
    }

    val commons: Collection<CommonBase> =
        listOf(swagger, openApi).filterNotNull()

    private val variations: Collection<BaseWithVariation<out CommonBase>>
        get() = commons.map {
            when (it) {
                is Swagger -> SwaggerBaseWithVariation(
                    it,
                    SpecVariation("#/definitions/", ResponseV2, OperationV2)
                )
                is OpenApi -> OpenApiBaseWithVariation(
                    it,
                    SpecVariation("#/components/schemas/", ResponseV3, OperationV3)
                )
                else -> throw IllegalStateException("Must be of type ${Swagger::class.simpleName} or ${OpenApi::class.simpleName}")
            }
        }

    /**
     * The [HttpMethod] types that don't support having a HTTP body element.
     */
    private val methodForbidsBody = setOf(HttpMethod.Get, HttpMethod.Delete)

    inline fun <reified LOCATION : Any, reified ENTITY_TYPE : Any> Metadata.apply(method: HttpMethod) {
        apply(LOCATION::class, typeInfo<ENTITY_TYPE>(), method)
    }

    private fun Metadata.createBodyType(typeInfo: TypeInfo): BodyType =
        bodySchema?.let {
            BodyFromSchema(
                name = bodySchema.name ?: typeInfo.modelName()
            )
        } ?: BodyFromReflection(typeInfo)

    private fun Metadata.requireMethodSupportsBody(method: HttpMethod) =
        require(!(methodForbidsBody.contains(method) && bodySchema != null)) {
            "Method type $method does not support a body parameter."
        }

    fun Metadata.apply(locationClass: KClass<*>, bodyTypeInfo: TypeInfo, method: HttpMethod) {
        requireMethodSupportsBody(method)
        val bodyType = createBodyType(bodyTypeInfo)
        val clazz = locationClass.java
        val location = clazz.getAnnotation(Location::class.java)
        val tags = clazz.getAnnotation(Group::class.java)

        variations.forEach {
            it.apply {
                applyOperations(location, tags, method, locationClass, bodyType)
            }
        }
    }
}

private class SwaggerBaseWithVariation(
    base: Swagger,
    variation: SpecVariation
) : BaseWithVariation<Swagger>(base, variation) {

    override val schemaHolder: MutableMap<ModelName, Any>
        get() = base.definitions

    override fun addDefinition(name: String, schema: Any) {
        base.definitions.putIfAbsent(name, schema)
    }
}

private class OpenApiBaseWithVariation(
    base: OpenApi,
    variation: SpecVariation
) : BaseWithVariation<OpenApi>(base, variation) {
    override val schemaHolder: MutableMap<ModelName, Any>
        get() = base.components.schemas

    override fun addDefinition(name: String, schema: Any) {
        base.components.schemas.putIfAbsent(name, schema)
    }
}

private abstract class BaseWithVariation<B : CommonBase>(
    val base: B,
    val variation: SpecVariation
) {
    abstract val schemaHolder: MutableMap<ModelName, Any>

    abstract fun addDefinition(name: String, schema: Any)

    fun addDefinition(typeInfo: TypeInfo) {
        if (typeInfo.type != Unit::class) {
            val accruedNewDefinitions = mutableListOf<TypeInfo>()
            schemaHolder
                .computeIfAbsent(typeInfo.modelName()) {
                    val modelWithAdditionalDefinitions = variation {
                        createModelData(typeInfo)
                    }
                    accruedNewDefinitions.addAll(modelWithAdditionalDefinitions.second)
                    modelWithAdditionalDefinitions.first
                }

            accruedNewDefinitions.forEach { addDefinition(it) }
        }
    }

    fun addDefinitions(kClasses: Collection<TypeInfo>) =
        kClasses.forEach {
            addDefinition(it)
        }

    fun <LOCATION : Any> Metadata.applyOperations(
        location: Location,
        group: Group?,
        method: HttpMethod,
        locationType: KClass<LOCATION>,
        bodyType: BodyType
    ) {

        when (bodyType) {
            is BodyFromReflection -> bodyType.typeInfo.let { typeInfo ->
                if (typeInfo.type != Unit::class) {
                    addDefinition(typeInfo)
                }
            }
        }

        fun createOperation(): OperationBase {
            val responses = responses.map { (status, type) ->
                val response = when (type) {
                    is ResponseFromReflection -> {
                        addDefinition(type.type)
                        variation.reponseCreator.create(status, type.type)
                    }
                    is ResponseSchema -> {
                        variation.reponseCreator.create(type.name)
                    }
                }

                status.value.toString() to response
            }.toMap()

            val parameters = mutableListOf<Parameter>().apply {
                variation {
                    if ((bodyType as? BodyFromReflection)?.typeInfo?.type != Unit::class) {
                        add(bodyType.bodyParameter())
                    }
                    addAll(locationType.memberProperties.map {
                        it.toParameter(location.path).let {
                            addDefinitions(it.second)
                            it.first
                        }
                    })
                    parameter?.let {
                        addAll(it.memberProperties.map {
                            it.toParameter(location.path, ParameterInputType.query).let {
                                addDefinitions(it.second)
                                it.first
                            }
                        })
                    }
                    headers?.let {
                        addAll(it.memberProperties.map {
                            it.toParameter(location.path, ParameterInputType.header).let {
                                addDefinitions(it.second)
                                it.first
                            }
                        })
                    }
                }
            }

            return variation.operationCreator.create(
                this,
                responses,
                parameters,
                location,
                group,
                method
            )
        }

        base.paths
            .getOrPut(location.path) { mutableMapOf() }
            .put(
                method.value.toLowerCase(),
                createOperation()
            )
    }
}

data class SwaggerUiConfiguration(
    var path: String = "apidocs",
    var forwardRoot: Boolean = false,
    var provideUi: Boolean = true,
    var swagger: Swagger? = null,
    var openApi: OpenApi? = null
)
