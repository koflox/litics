package com.deliveryhero.litics

import com.charleskorn.kaml.Yaml
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.KModifier.OVERRIDE
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

private const val PACKAGE_LITICS = "com.deliveryhero.litics"

private const val EVENT_TRACKER_CLASS_NAME = "EventTracker"
private const val EVENT_TRACKERS_PROPERTY_NAME = "eventTrackers"

@Serializable
data class Events(
    val components: Components = Components(),
    @SerialName("events")
    val methodNameToEvents: Map<String, Event>,
)

@Serializable
data class Components(
    val parameters: Map<String, Map<String, Event.Parameter>> = emptyMap(),
)

@Serializable
data class Event(
    val name: String,
    val description: String,
    @SerialName("supported_platforms")
    val supportedPlatforms: List<String>,
    val parameters: Map<String, Parameter> = emptyMap(),
) {

    @Serializable
    data class Parameter(
        val description: String? = null,
        val type: String,
        val required: Boolean,
        val default: String? = null,
        val example: String? = null,
    )
}

private data class EventDefinition(
    val methodName: String,
    val methodDoc: String,
    val name: String,
    val parameters: List<ParamDefinition>,
    val supportedPlatforms: List<String>,
)

private data class ParamDefinition(
    val name: String,
    val kdoc: String?,
    val type: String,
    val isRequired: Boolean,
    val defaultValue: String?,
)

object EventsGenerator {

    private lateinit var platform: Platform

    fun generate(platform: Platform, packageName: String, sourceFile: File, targetDirectory: File) {
        this.platform = platform
        val eventDefinitions = buildEventDefinitions(sourceFile)
        val generatedEventAnalyticsAbstractClass = ClassName(packageName, "GeneratedEventsAnalytics")

        createGeneratedEventsAnalyticsFileSpec(eventDefinitions, generatedEventAnalyticsAbstractClass).writeTo(targetDirectory)
        createGeneratedEventsAnalyticsImplFileSpec(packageName, eventDefinitions, generatedEventAnalyticsAbstractClass).writeTo(targetDirectory)
    }

    private fun createGeneratedEventsAnalyticsFileSpec(eventDefinitions: List<EventDefinition>, generatedEventAnalyticsAbstractClass: ClassName): FileSpec {
        val funSpecs = buildFunSpecs(eventDefinitions)

        val interfaceTypeSpec = with(TypeSpec.classBuilder(generatedEventAnalyticsAbstractClass)) {
            addModifiers(ABSTRACT)
            if (platform == Platform.JS) {
                addAnnotation(ClassName("kotlin.js", "JsExport"))
            }
            addFunctions(funSpecs)
            build()
        }

        return FileSpec
            .builder(generatedEventAnalyticsAbstractClass.packageName, generatedEventAnalyticsAbstractClass.simpleName)
            .addType(interfaceTypeSpec)
            .build()
    }

    private fun createGeneratedEventsAnalyticsImplFileSpec(packageName: String, eventDefinitions: List<EventDefinition>, generatedEventAnalyticsAbstractClass: ClassName): FileSpec {
        val eventTracker = ClassName(PACKAGE_LITICS, EVENT_TRACKER_CLASS_NAME)
        val eventTrackers = ARRAY.parameterizedBy(eventTracker)

        val generatedEventAnalyticsClass = ClassName(packageName, "GeneratedEventsAnalyticsImpl")

        val interfaceImplTypeSpec = buildInterfaceImplTypeSpec(
            generatedEventAnalyticsAbstractClass,
            generatedEventAnalyticsClass,
            eventTrackers,
            buildFunImplSpecs(eventDefinitions),
        )

        return FileSpec
            .builder(generatedEventAnalyticsClass.packageName, generatedEventAnalyticsClass.simpleName)
            .addType(interfaceImplTypeSpec)
            .build()
    }

    private fun buildInterfaceImplTypeSpec(
        generatedEventAnalyticsAbstractClass: ClassName,
        generatedEventAnalyticsClass: ClassName,
        eventTrackersParameterizedTypeName: ParameterizedTypeName,
        funImplSpecs: List<FunSpec>,
    ): TypeSpec {

        //Make constructor for GeneratedEventsAnalyticsImpl
        val constructorFunSpec = FunSpec.constructorBuilder()
            .addParameter(EVENT_TRACKERS_PROPERTY_NAME, eventTrackersParameterizedTypeName)
            .build()

        //Make eventTrackers property for GeneratedEventsAnalyticsImpl
        val eventTrackersPropertySpec =
            PropertySpec.builder(EVENT_TRACKERS_PROPERTY_NAME, eventTrackersParameterizedTypeName)
                .initializer(EVENT_TRACKERS_PROPERTY_NAME)
                .addModifiers(KModifier.PRIVATE)
                .build()

        //Make class GeneratedEventsAnalyticsImpl
        return with(TypeSpec.classBuilder(generatedEventAnalyticsClass)) {
            if (platform == Platform.JS) {
                addAnnotation(ClassName("kotlin.js", "JsExport"))
            }
            primaryConstructor(constructorFunSpec)
            superclass(generatedEventAnalyticsAbstractClass)
            addProperty(eventTrackersPropertySpec)
            addFunctions(funImplSpecs)
            build()
        }
    }

    private fun buildFunSpecs(eventDefinitions: List<EventDefinition>): List<FunSpec> {
        return eventDefinitions.map { eventDefinition ->
            val interfaceFunParamsSpecs = eventDefinition.parameters
                .map { paramDefinition -> buildParamSpec(paramDefinition, canAddDefault = true) }

            buildFuncSpec(eventDefinition.methodName, eventDefinition.methodDoc, interfaceFunParamsSpecs)
        }
    }


    private fun buildFunImplSpecs(eventDefinitions: List<EventDefinition>): List<FunSpec> {
        return eventDefinitions
            .map { eventDefinition ->
                val implFunParamSpecs: List<ParameterSpec> = eventDefinition.parameters
                    .map { paramDefinition -> buildParamSpec(paramDefinition, canAddDefault = false) }

                buildFuncImplSpec(
                    eventDefinition.methodName,
                    eventDefinition.name,
                    implFunParamSpecs,
                    eventDefinition.supportedPlatforms
                )
            }
    }

    private fun buildFuncSpec(
        methodName: String,
        methodDoc: String,
        funParams: List<ParameterSpec>,
    ): FunSpec =
        FunSpec.builder(methodName)
            .addModifiers(ABSTRACT)
            .addKdoc(methodDoc)
            .addParameters(funParams)
            .build()

    private fun buildFuncImplSpec(
        methodName: String,
        eventName: String,
        funParamsSpecs: List<ParameterSpec>,
        supportedPlatforms: List<String>,
    ): FunSpec {
        val trackingEvent = ClassName(PACKAGE_LITICS, "TrackingEvent")
        val trackingEventParameter = trackingEvent.nestedClass("Parameter")

        return FunSpec.builder(methodName)
            .addModifiers(OVERRIDE)
            .addParameters(funParamsSpecs)
            .addStatement("val params = mutableListOf<%T>()", trackingEventParameter)
            .addCode(buildCodeBlock {
                funParamsSpecs.forEach {
                    if (it.type.isNullable) {
                        beginControlFlow("if (%L != null)", it.name)
                        addStatement("params += %T(%S, %L)", trackingEventParameter, it.name, it.name)
                        endControlFlow()
                    } else {
                        addStatement("params += %T(%S, %L)", trackingEventParameter, it.name, it.name)
                    }
                }
            })
            .addCode(buildCodeBlock {
                val arrayOf = MemberName("kotlin", "arrayOf")
                val paramCodeBlocks = supportedPlatforms.map { CodeBlock.of("%S", it) }
                addStatement("val supportedPlatforms = %M(%L)", arrayOf, paramCodeBlocks.joinToCode())
            })
            .addStatement("val trackingEvent = %T(%S, params.toTypedArray())", trackingEvent, eventName)
            .addStatement("eventTrackers.filter·{ it.supportsEventTracking(supportedPlatforms) }.forEach·{ it.trackEvent(trackingEvent) }")
            .build()
    }

    // The canAddDefault variable is required as overridden methods cannot have default values
    private fun buildParamSpec(paramDefinition: ParamDefinition, canAddDefault: Boolean): ParameterSpec {
        val builder = ParameterSpec
            .builder(
                name = paramDefinition.name,
                type = STRING.copy(nullable = !paramDefinition.isRequired)
            )

        if (paramDefinition.kdoc != null) {
            builder.addKdoc(paramDefinition.kdoc)
        }

        if (paramDefinition.defaultValue != null && canAddDefault) {
            builder.defaultValue(paramDefinition.defaultValue)
        }

        return builder.build()
    }

    private fun buildEventDefinitions(file: File): List<EventDefinition> {
        return Yaml.default.decodeFromStream(Events.serializer(), file.inputStream())
            .methodNameToEvents
            .map { (methodName, event) ->
                EventDefinition(
                    methodName = methodName,
                    methodDoc = event.description,
                    name = event.name,
                    parameters = event.parameters
                        .map { (parameterName, parameter) ->
                            ParamDefinition(
                                name = parameterName,
                                kdoc = parameter.description,
                                type = parameter.type,
                                isRequired = parameter.required,
                                defaultValue = parameter.default,
                            )
                        },
                    supportedPlatforms = event.supportedPlatforms
                )
            }
    }
}
